import DefaultTheme from 'vitepress/theme'
import MarkdownActions from './components/MarkdownActions.vue'
import { h } from 'vue'

export default {
  extends: DefaultTheme,
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'aside-outline-before': () => h(MarkdownActions, { mode: 'aside' }),
      'doc-before': () => h(MarkdownActions, { mode: 'doc' })
    })
  }
}
