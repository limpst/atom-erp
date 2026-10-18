package com.atomeng.erp.fcd.domain.features;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 공휴일 캘린더 (대체공휴일 · 임시공휴일 포함). 설계는 core 캘린더 조회지만 core 에 아직 없어
 * {@code fcd/kr-holidays.csv}(프로토타입의 holidays.KR 2024–2028 내보내기)를 쓴다 — core 캘린더 생기면 교체.
 */
public final class HolidayCalendar {

    private final Set<LocalDate> days;

    public HolidayCalendar(Set<LocalDate> days) {
        this.days = Set.copyOf(days);
    }

    public static HolidayCalendar fromClasspath() {
        try (InputStream in = Objects.requireNonNull(HolidayCalendar.class.getResourceAsStream("/fcd/kr-holidays.csv"));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Set<LocalDate> s = new HashSet<>();
            r.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> s.add(LocalDate.parse(l.split(",")[0])));
            return new HolidayCalendar(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isHoliday(LocalDate d) {
        return days.contains(d);
    }

    public boolean isOffday(LocalDate d) {
        return d.getDayOfWeek().getValue() >= 6 || isHoliday(d);
    }

    /** 영업일 n일 후 (소명 기한 BR-FCD-011) */
    public LocalDate addBusinessDays(LocalDate d, int n) {
        LocalDate cur = d;
        while (n > 0) {
            cur = cur.plusDays(1);
            if (!isOffday(cur)) {
                n--;
            }
        }
        return cur;
    }
}
