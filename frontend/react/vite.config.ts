import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// `base` is where the built files are served from. On GitHub Pages that is a
// sub-path (/lms/react/), so asset URLs in index.html must start with it.
export default defineConfig({
  base: process.env.BASE_PATH ?? '/',
  plugins: [react()],
});
