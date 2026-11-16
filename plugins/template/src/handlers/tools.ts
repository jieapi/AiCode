import { type PluginHandler } from '@jieapi/plugin-sdk';

export const tools: Record<string, PluginHandler> = {
  'tools.echo': (args) => ({ text: String(args.text ?? '') })
};
