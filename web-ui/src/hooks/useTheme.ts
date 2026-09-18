import { useEffect, useState } from "react";
import {
  type Theme,
  THEME_COOKIE,
  getCookie,
  setCookie,
  resolveInitialTheme,
} from "@/lib/theme";

export type { Theme };

function readTheme(): Theme {
  const raw = getCookie(THEME_COOKIE);
  return raw === "dark" || raw === "light" ? raw : resolveInitialTheme();
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(readTheme);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    setCookie(THEME_COOKIE, theme);
  }, [theme]);

  const toggle = () => setTheme((t) => (t === "dark" ? "light" : "dark"));

  return { theme, toggle };
}
