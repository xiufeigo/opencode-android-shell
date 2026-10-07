(() => {
  const r = (el) => { const b = el.getBoundingClientRect(); return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) }; };
  const form = document.querySelector('[data-component="composer"]');
  const out = { form: form ? r(form) : null, nodes: [] };
  if (form) {
    for (const el of form.querySelectorAll("*")) {
      const b = el.getBoundingClientRect();
      if (b.width < 10 || b.height < 10) continue;
      const tag = el.tagName;
      if (tag !== "INPUT" && tag !== "TEXTAREA" && !el.isContentEditable && tag !== "BUTTON" && !el.getAttribute("data-slot") && !el.getAttribute("data-component")) continue;
      out.nodes.push({
        tag, editable: el.isContentEditable || tag === "INPUT" || tag === "TEXTAREA",
        comp: el.getAttribute("data-component") || "", slot: el.getAttribute("data-slot") || "",
        cls: String(el.className || "").slice(0, 60), rect: r(el),
        text: (el.innerText || "").replace(/\s+/g, " ").slice(0, 30),
      });
    }
  }
  return JSON.stringify(out);
})()
