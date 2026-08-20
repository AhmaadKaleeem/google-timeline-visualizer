import { defineConfig } from 'vitest/config';

export default defineConfig({
  base: '/google-timeline-visualizer/',
  build: {
    target: 'safari16.4',
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
