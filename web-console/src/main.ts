const root = document.querySelector<HTMLElement>("#app");

if (root === null) {
  throw new Error("Missing #app root");
}

root.textContent =
  "Drone Agent Companion workspace scaffold loaded; the live console is implemented by issues #3 and #4.";
