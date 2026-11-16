import { definePlugin } from '@jieapi/plugin-sdk';
import { tools } from './handlers/tools';

definePlugin({
  handlers: tools
});
