# Responsive Content Width Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce excessive side whitespace while keeping the GaussDB comparison UI usable on small displays.

**Architecture:** Keep the existing single-page HTML/CSS structure. Replace the fixed 1280px container constraint with a fluid width, a 1360px cap, and a narrow-screen override; leave table and interaction logic unchanged.

**Tech Stack:** Static HTML/CSS, existing Spring Boot static resources, Playwright/browser viewport checks.

---

### Task 1: Update the shared page container

**Files:**
- Modify: `src/main/resources/static/index.html` near the global `.container` rule
- Test: Browser viewport screenshots at 1280x720, 1366x768, and 375x812

- [ ] **Step 1: Change the container sizing rule**

Replace the existing fixed rule:

```css
.container { max-width: 1280px; margin: 0 auto; padding: 24px; }
```

with:

```css
.container {
  width: calc(100% - 32px);
  max-width: 1360px;
  margin: 0 auto;
  padding: 24px 0;
}
@media (max-width: 720px) {
  .container {
    width: calc(100% - 24px);
    padding: 16px 0;
  }
}
```

- [ ] **Step 2: Verify the layout at desktop and mobile widths**

Run the local app and inspect the page at `1280x720`, `1366x768`, and `375x812`. Confirm the page itself has no horizontal scrollbar and the main table retains its existing local overflow behavior.

- [ ] **Step 3: Run repository checks**

Run:

```bash
mvn test -q
```

Expected: existing tests pass; no JavaScript or backend behavior changes are introduced.

- [ ] **Step 4: Commit the focused implementation**

```bash
git add src/main/resources/static/index.html
git commit -m "fix: reduce page side whitespace responsively"
```
