import { computed, inject, readonly, ref } from "vue";
import zhSite from "../locales/zh-CN/site.js";
import zhDemo from "../locales/zh-CN/demo.js";
import enSite from "../locales/en/site.js";
import enDemo from "../locales/en/demo.js";

export const DEFAULT_LOCALE = "zh-CN";
export const LOCALE_PATHS = { "zh-CN": "/", en: "/en/" };
export const LOCALE_KEY = Symbol("website-locale");

export const translations = {
  "zh-CN": { site: zhSite, demo: zhDemo },
  en: { site: enSite, demo: enDemo }
};

export function getLocaleFromPath(pathname) {
  return ["/en", "/en/", "/en/index.html"].includes(pathname) ? "en" : DEFAULT_LOCALE;
}

export function createLocale(initialLocale = DEFAULT_LOCALE) {
  const locale = ref(initialLocale === "en" ? "en" : DEFAULT_LOCALE);
  const suffix = ref("");
  const copy = computed(() => translations[locale.value]);
  const alternateLocale = computed(() => locale.value === "zh-CN" ? "en" : "zh-CN");
  const alternateHref = computed(() => LOCALE_PATHS[alternateLocale.value] + suffix.value);

  function setLocale(value) {
    if (value === "zh-CN" || value === "en") locale.value = value;
  }

  function syncLocation({ pathname, search = "", hash = "" }) {
    setLocale(getLocaleFromPath(pathname));
    suffix.value = search + hash;
  }

  return { locale: readonly(locale), copy, setLocale, syncLocation, alternateLocale, alternateHref };
}

export function useLocale() {
  return inject(LOCALE_KEY);
}
