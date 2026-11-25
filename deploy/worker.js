/**
 * SokeriPilot landing — Cloudflare Worker with static assets.
 *
 * Files in ./landing are served directly by the asset layer (index.html,
 * styles.css, app.js, the hero image and the signed APK). This handler adds one
 * thing on top: a missing path answers with the branded 404 page and a real 404
 * status, instead of the asset layer's bare default.
 */
export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request);
    if (response.status !== 404) return response;

    const url = new URL(request.url);
    if (url.pathname === "/404.html") return response;

    const page = await env.ASSETS.fetch(new Request(new URL("/404.html", url)));
    if (page.status !== 200) return response;
    return new Response(page.body, { status: 404, headers: page.headers });
  },
};
