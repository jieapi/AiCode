const observerKey = "__revealObserver";

export const vReveal = {
  mounted(el) {
    if (typeof IntersectionObserver === "undefined") {
      el.classList.add("is-visible");
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          el.classList.add("is-visible");
          observer.unobserve(el);
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -30px 0px" }
    );

    observer.observe(el);
    el[observerKey] = observer;
  },
  unmounted(el) {
    el[observerKey]?.disconnect();
  }
};
