import DefaultTheme from 'vitepress/theme'
import MarkdownActions from './components/MarkdownActions.vue'
import TranslationBanner from './components/TranslationBanner.vue'
import BilibiliEmbed from './components/BilibiliEmbed.vue'
import { h } from 'vue'
import { inject } from '@vercel/analytics'
import type { App } from 'vue'

export default {
  extends: DefaultTheme,
  Layout() {
    return h(DefaultTheme.Layout, null, {
      'home-hero-before': () => h(TranslationBanner),
      'aside-outline-before': () => h(MarkdownActions, { mode: 'aside' }),
      'doc-before': () => [h(TranslationBanner), h(MarkdownActions, { mode: 'doc' })]
    })
  },
  enhanceApp({ app }: { app: App }) {
    app.component('BilibiliEmbed', BilibiliEmbed)
    if (typeof window !== 'undefined') {
      inject()
    }
  }
}
