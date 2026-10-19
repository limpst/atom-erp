import { AgGridReact, type AgGridReactProps } from 'ag-grid-react';
import { colorSchemeDark, colorSchemeLight, themeQuartz } from 'ag-grid-community';
import { useThemeContext } from '@/app/ThemeContext';

const light = themeQuartz.withPart(colorSchemeLight).withParams({ fontFamily: 'inherit', fontSize: 13 });
const dark = themeQuartz.withPart(colorSchemeDark).withParams({ fontFamily: 'inherit', fontSize: 13 });

/** AG Grid Community 래퍼 — 앱 테마(light/dark)를 따른다 */
export function DataGrid<T>(props: AgGridReactProps<T> & { height?: number | string; testId?: string }) {
  const { theme } = useThemeContext();
  const { height = 420, testId, ...rest } = props;
  return (
    <div style={{ height, width: '100%' }} data-testid={testId}>
      <AgGridReact<T>
        theme={theme === 'dark' ? dark : light}
        localeText={{ noRowsToShow: '데이터가 없습니다', loadingOoo: '불러오는 중…' }}
        animateRows={false}
        {...rest}
      />
    </div>
  );
}
