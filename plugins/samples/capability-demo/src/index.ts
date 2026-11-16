import { definePlugin } from '@aicode/plugin-sdk';
import { actions } from './handlers/actions';
import { tools } from './handlers/tools';

definePlugin({
  handlers: { ...tools, ...actions }
});
