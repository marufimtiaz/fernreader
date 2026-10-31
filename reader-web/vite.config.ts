import { defineConfig } from 'vite'

export default defineConfig({
  root: 'src',
  base: './',
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    target: 'es2020',
    modulePreload: false,
    rollupOptions: {
      // foliate-js lazily imports archive/PDF helpers we never ship; books come from our own loader.
      external: [/vendor\/zip\.js$/, /vendor\/fflate\.js$/, /vendor\/pdfjs\//, /\/(pdf|comic-book|fb2|mobi|tts)\.js$/],
    },
  },
  test: { root: '.', environment: 'jsdom', include: ['test/**/*.test.ts'] },
})
