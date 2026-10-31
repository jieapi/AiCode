import { onMounted, ref } from "vue";

const STORAGE_KEY = "aicode-site-theme";

export function useTheme() {
  const theme = ref("light");

  onMounted(() => {
    if (document.documentElement.getAttribute("data-theme") === "dark") {
      theme.value = "dark";
    }
  });

  function toggleTheme() {
    theme.value = theme.value === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", theme.value);
    try {
      localStorage.setItem(STORAGE_KEY, theme.value);
    } catch (error) {
      // 隐私模式下 localStorage 可能不可写，忽略即可
    }
  }

  return { theme, toggleTheme };
}
