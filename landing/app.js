(function () {
  "use strict";

  var root = document.documentElement;
  root.classList.add("js");

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  /* Sticky nav state */
  var nav = document.querySelector(".nav");
  if (nav) {
    var syncNav = function () {
      nav.classList.toggle("is-scrolled", window.scrollY > 12);
    };
    syncNav();
    window.addEventListener("scroll", syncNav, { passive: true });
  }

  /* Reveal on scroll */
  var revealItems = Array.prototype.slice.call(document.querySelectorAll(".reveal"));

  if (!("IntersectionObserver" in window) || reduceMotion.matches) {
    revealItems.forEach(function (item) {
      item.classList.add("is-in");
    });
  } else {
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-in");
          observer.unobserve(entry.target);
        });
      },
      { threshold: 0.14, rootMargin: "0px 0px -6% 0px" }
    );
    revealItems.forEach(function (item) {
      observer.observe(item);
    });
  }

  /* Reading progress */
  var progressBar = document.querySelector(".progress__bar");
  if (progressBar) {
    var syncProgress = function () {
      var max = document.documentElement.scrollHeight - window.innerHeight;
      progressBar.style.width = (max > 0 ? Math.min(100, (window.scrollY / max) * 100) : 0) + "%";
    };
    syncProgress();
    window.addEventListener("scroll", syncProgress, { passive: true });
    window.addEventListener("resize", syncProgress, { passive: true });
  }

  /* Subtle 3D tilt for the product scenes (pointer devices, motion allowed) */
  if (!reduceMotion.matches && window.matchMedia("(pointer: fine)").matches) {
    document.querySelectorAll("[data-tilt]").forEach(function (scene) {
      var strength = Number(scene.getAttribute("data-tilt")) || 5;
      scene.addEventListener("pointermove", function (event) {
        var rect = scene.getBoundingClientRect();
        var x = (event.clientX - rect.left) / rect.width - 0.5;
        var y = (event.clientY - rect.top) / rect.height - 0.5;
        scene.style.setProperty("--tx", (x * strength).toFixed(2) + "deg");
        scene.style.setProperty("--ty", (-y * strength).toFixed(2) + "deg");
      });
      scene.addEventListener("pointerleave", function () {
        scene.style.removeProperty("--tx");
        scene.style.removeProperty("--ty");
      });
    });
  }

  /* Head-unit overlay: keep the cockpit plane locked to the photo's screen */
  var shotFrame = document.querySelector(".shot__frame");
  if (shotFrame) {
    var syncShotScale = function () {
      var scale = shotFrame.clientWidth / 1392;
      shotFrame.style.setProperty("--shot-scale", scale.toFixed(4));
    };
    syncShotScale();
    window.addEventListener("resize", syncShotScale, { passive: true });
  }

  /* Footer year */
  var year = document.getElementById("year");
  if (year) year.textContent = String(new Date().getFullYear());

  /* In-page anchors: smooth scroll + keyboard focus target */
  document.querySelectorAll('a[href^="#"]').forEach(function (link) {
    link.addEventListener("click", function (event) {
      var hash = link.getAttribute("href");
      if (!hash || hash === "#") return;
      var target = document.querySelector(hash);
      if (!target) return;
      event.preventDefault();
      target.scrollIntoView({
        behavior: reduceMotion.matches ? "auto" : "smooth",
        block: "start"
      });
      target.setAttribute("tabindex", "-1");
      target.focus({ preventScroll: true });
    });
  });
})();
