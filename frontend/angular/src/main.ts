import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig)
  // Labels, help and error links for every field, success toasts, and a held submit button (shared/fields.ts); loaded
  // after the first render so it stays out of the initial bundle.
  .then(() => import('../../shared/fields').then((m) => m.installFormHelpers()))
  .catch((err) => console.error(err));
