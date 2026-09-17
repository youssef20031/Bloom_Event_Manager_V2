import { useCallback, useEffect, useState } from "react";

export type Theme = "light" | "dark";

const STORAGE_KEY = "theme";

function getInitialTheme(): Theme {
    // Bloom is committed to a single light theme (matches the landing page).
    return "light";
}

/**
 * Light/dark theme controller for the glassmorphism UI.
 *
 * Reflects the active theme onto <html data-theme="…"> (which the design tokens
 * key off) and persists the user's choice in localStorage. Initial value falls
 * back to the OS `prefers-color-scheme` when nothing is stored.
 */
export function useTheme() {
    const [theme, setThemeState] = useState<Theme>(getInitialTheme);

    useEffect(() => {
        document.documentElement.setAttribute("data-theme", theme);
        try {
            window.localStorage.setItem(STORAGE_KEY, theme);
        } catch {
            /* ignore storage failures (e.g. private mode) */
        }
    }, [theme]);

    const setTheme = useCallback((next: Theme) => setThemeState(next), []);

    const toggleTheme = useCallback(
        () => setThemeState((prev) => (prev === "dark" ? "light" : "dark")),
        [],
    );

    return { theme, setTheme, toggleTheme };
}
