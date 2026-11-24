/**
 * GlucoPilot landing — Cloudflare Worker with static assets.
 *
 * Files in ./landing are served directly by the asset layer (index.html,
 * styles.css, app.js, the hero image and the signed APK). This handler is the
 * fallback for paths that are not backed by an asset, so the response stays
 * consistent with the asset layer instead of inventing routes.
 */
export default {
  async fetch(request, env) {
    return env.ASSETS.fetch(request);
  },
};
