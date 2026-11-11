globalThis.aicode = {
  lastRun: null,
  handlers: {
    "tools.echo": (args) => {
      const text = String(args.text ?? "");
      return {
        text: text,
        length: text.length,
        plugin: "community.demo-kit",
        runtime: "javascript"
      };
    },
    "tools.lastRun": () => globalThis.aicode.lastRun,
    "hooks.runFinished": (args) => {
      globalThis.aicode.lastRun = args;
      return "stored";
    }
  }
};
