package com.atomeng.common.events;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 1단계 전송: 같은 JVM 의 {@code @EventListener} 에 동기 전달.
 *
 * <p>보안 검토 CMN-14: 빈 팩토리를 받으면 {@link SubscriberTransport} — {@code EventEnvelope} 하나를 받는
 * {@code @EventListener} 메서드를 구독자로 찾아(조건 {@code condition} SpEL 은 Spring 과 같은 규칙으로 평가)
 * Relay 가 구독자마다 따로(별도 트랜잭션으로) 부르게 한다. 구독자 id = {@code @IdempotentListener.consumer}
 * (없으면 {@code 빈이름#메서드}). 빈 팩토리 없이 만들면 예전처럼 {@link #send} 로 전체 구독자에 한 번에 전달.
 */
public class SpringEventTransport implements SubscriberTransport {

    private static final SpelExpressionParser SPEL = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private final ApplicationEventPublisher publisher;
    private final ConfigurableListableBeanFactory beans;
    private volatile List<MethodSubscriber> subscribers;

    public SpringEventTransport(ApplicationEventPublisher publisher) {
        this(publisher, null);
    }

    public SpringEventTransport(ApplicationEventPublisher publisher, ConfigurableListableBeanFactory beans) {
        this.publisher = publisher;
        this.beans = beans;
    }

    /** 구독자 단위 전달 가능 여부(빈 팩토리가 있을 때) */
    public boolean perSubscriber() {
        return beans != null;
    }

    @Override
    public void send(EventEnvelope envelope) {
        publisher.publishEvent(envelope);
    }

    @Override
    public List<Subscriber> subscribersFor(EventEnvelope envelope) {
        List<Subscriber> out = new ArrayList<>();
        PayloadApplicationEvent<EventEnvelope> event = new PayloadApplicationEvent<>(this, envelope);
        for (MethodSubscriber s : discover()) {
            boolean match;
            try {
                match = s.matches(event, envelope);
            } catch (RuntimeException conditionError) {
                // 조건 평가 오류는 그 구독자 실패로 기록(재시도 · DLQ) — outbox 전체를 멈추지 않는다
                out.add(new Subscriber() {
                    @Override
                    public String id() {
                        return s.id();
                    }

                    @Override
                    public void deliver(EventEnvelope env) {
                        throw conditionError;
                    }
                });
                continue;
            }
            if (match) {
                out.add(s);
            }
        }
        return out;
    }

    private List<MethodSubscriber> discover() {
        List<MethodSubscriber> s = subscribers;
        if (s == null) {
            synchronized (this) {
                s = subscribers;
                if (s == null) {
                    s = scan();
                    subscribers = s;
                }
            }
        }
        return s;
    }

    private List<MethodSubscriber> scan() {
        List<MethodSubscriber> found = new ArrayList<>();
        for (String name : beans.getBeanNamesForType(Object.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(name)) {
                continue;
            }
            Class<?> type;
            try {
                type = AutoProxyUtils.determineTargetClass(beans, name);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null || !AnnotationUtilsHolder.isCandidate(type)) {
                continue;
            }
            Map<Method, EventListener> methods;
            try {
                methods = MethodIntrospector.selectMethods(type, (MethodIntrospector.MetadataLookup<EventListener>)
                        m -> AnnotatedElementUtils.findMergedAnnotation(m, EventListener.class));
            } catch (Throwable unresolvable) {
                continue;
            }
            methods.forEach((m, ann) -> {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == EventEnvelope.class) {
                    IdempotentListener il = AnnotatedElementUtils.findMergedAnnotation(m, IdempotentListener.class);
                    String id = il != null ? il.consumer() : name + "#" + m.getName();
                    found.add(new MethodSubscriber(id, name, m, ann.condition()));
                }
            });
        }
        found.sort((a, b) -> AnnotationAwareOrderComparator.INSTANCE.compare(a.order(), b.order()));
        return List.copyOf(found);
    }

    /** 이름 공간 정리용 — 스프링 내부 · JDK 타입은 건너뛴다 */
    private static final class AnnotationUtilsHolder {
        static boolean isCandidate(Class<?> type) {
            String n = type.getName();
            return !n.startsWith("java.") && !n.startsWith("org.springframework.");
        }
    }

    private final class MethodSubscriber implements Subscriber {
        private final String id;
        private final String beanName;
        private final Method method;
        private final Expression condition;
        private volatile Method invocable;

        MethodSubscriber(String id, String beanName, Method method, String condition) {
            this.id = id;
            this.beanName = beanName;
            this.method = method;
            this.condition = StringUtils.hasText(condition) ? SPEL.parseExpression(condition) : null;
        }

        Integer order() {
            Order o = AnnotatedElementUtils.findMergedAnnotation(method, Order.class);
            return o == null ? Integer.MAX_VALUE : o.value();
        }

        @Override
        public String id() {
            return id;
        }

        boolean matches(PayloadApplicationEvent<EventEnvelope> event, EventEnvelope env) {
            if (condition == null) {
                return true;
            }
            // Spring EventExpressionRootObject 와 같은 모양(#root.event · #root.args · #파라미터이름)
            var ctx = new MethodBasedEvaluationContext(new Root(event, new Object[] {env}), method, new Object[] {env}, NAMES);
            Object v = condition.getValue(ctx);
            return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
        }

        @Override
        public void deliver(EventEnvelope envelope) throws Exception {
            Object bean = beans.getBean(beanName);
            Method m = invocable;
            if (m == null) {
                m = AopUtils.selectInvocableMethod(method, bean.getClass());
                ReflectionUtils.makeAccessible(m);
                invocable = m;
            }
            try {
                m.invoke(bean, envelope);
            } catch (InvocationTargetException e) {
                Throwable t = e.getTargetException();
                if (t instanceof Exception ex) {
                    throw ex;
                }
                throw (Error) t;
            }
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** SpEL 루트 — Spring 의 EventExpressionRootObject(event, args) 와 같은 속성 */
    public record Root(Object event, Object[] args) {}
}
