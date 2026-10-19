import { createContext, useContext, type ReactNode } from 'react';
import { useTheme, type Theme } from './theme';

const Ctx = createContext<{ theme: Theme; toggle: () => void }>({ theme: 'light', toggle: () => {} });

export function ThemeProvider({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Ctx.Provider value={t}>{children}</Ctx.Provider>;
}
export const useThemeContext = () => useContext(Ctx);
