# Aha System -- Frontend Development Guidelines

Read this document fully before writing any frontend code. These guidelines are the
authoritative reference for all frontend work on this project. Follow them exactly.

---

## 1. Icons

- **Only use MDI icons** (`@mdi/js` + `@mdi/react` or the local `components/ui/Icon.jsx` wrapper).
- Never use Lucide, Heroicons, FontAwesome, or any other icon library for new code.
  `lucide-react` is a legacy dependency used in some workflow components -- do not add
  new Lucide imports.
- **No emojis anywhere** -- not in UI, not in code comments, not in commit messages.

## 2. Styling System

The project uses a layered CSS architecture. No Tailwind -- it was fully removed.

### File structure and import order (critical)

```
src/styles/globals.css      -- Resets, :root, .App layout, scrollbars, accessibility
src/styles/themes.css       -- 13 themes via [data-theme="xxx"] selectors
src/styles/utilities.css    -- Prose/markdown, keyframes, shared utility classes
src/styles/buttons.css      -- .btn system (.btn-primary, .btn-secondary, etc.)
src/styles/editor.css       -- TipTap editor styles (KB + Procedures)
src/App.css                 -- Remaining global component styles (shrinking)
```

Import order in `main.jsx`: `globals -> themes -> utilities -> buttons -> editor -> App`.
This order matters -- later files can override earlier ones.

### CSS Modules for components

Every component with non-trivial styling should have a `ComponentName.module.css` file.

```jsx
import s from './MyComponent.module.css'

export default function MyComponent() {
  return <div className={s.root}>...</div>
}
```

- Use short import alias (`s`, `css`, or descriptive like `dd` for Dropdown).
- Access classes as `s.className` -- never string-interpolate module class names.
- For global classes inside a module, use `:global(.className)`.

### When to use what

| Approach | When to use |
|---|---|
| CSS Module class | Static styles specific to one component |
| Global class (App.css / utilities.css) | Shared across many components (`.btn`, `.prose`, `.error`) |
| Inline `style={{}}` | Truly dynamic values (depends on JS state/props/API data) |
| `data-` attribute + CSS | Boolean/enum states: `data-active`, `data-status="error"` |

### What NOT to do

- No `<style>` tags in JSX -- all CSS goes in `.css` files
- No `document.createElement('style')` injections
- No `!important` -- fix specificity instead
- No Tailwind classes (the library is removed)
- No global element selectors (`button {}`, `input {}`) -- use explicit classes
- No new CSS in `globals.css` unless it's truly global (resets, `:root`, `@media`)

## 3. Theme System

Themes use the `data-theme` attribute on the root `.App` div:

```jsx
<div className="App" data-theme={theme}>
```

- 20 themes: ocean (default), midnight, eclipse, slate, bml-red, ember, galactic,
  brass, forest, cyberpunk, arcane, dark-fantasy, aurora, pinky (dark);
  light, sand, sage, lavender, stone, rose (light).
- Ocean is the default -- its variables are on the `.App` selector.
- Other themes use `[data-theme="xxx"]` selectors in `themes.css`.
- Theme-specific overrides in other CSS files: `[data-theme="light"] .myClass { ... }`
- **Portal propagation**: when rendering via `createPortal`, copy the data-theme:
  ```jsx
  <div className="App" data-theme={document.querySelector('.App')?.getAttribute('data-theme') || 'ocean'}>
  ```
- Theme storage: `localStorage.getItem('theme')`, synced to backend via `PUT /api/auth/me`.
- **Portal theme propagation**: Any content rendered via `createPortal` to
  `document.body` sits outside the `.App` element and loses access to theme
  CSS variables. Always wrap portal content in a themed container:
  ```jsx
  createPortal(
    <div className="App" data-theme={document.querySelector('.App')?.getAttribute('data-theme') || 'ocean'}
         style={{ display: 'contents' }}>
      {/* portal content here -- theme variables now cascade */}
    </div>,
    document.body
  )
  ```
  This pattern is used by Sidebar (flyout + profile popover) and Dropdown (menu).

## 4. Button System

All buttons must use the `.btn` base class plus a variant:

```jsx
<button className="btn btn-primary">Save</button>
<button className="btn btn-secondary">Cancel</button>
<button className="btn btn-ghost">Close</button>
<button className="btn btn-danger">Delete</button>
<button className="btn btn-ghost btn-icon"><Icon .../></button>
```

| Variant | Use for |
|---|---|
| `.btn-primary` | Primary actions (save, confirm, submit) -- glass gradient |
| `.btn-secondary` | Secondary actions (cancel, back) -- subtle border |
| `.btn-ghost` | Tertiary actions, icon buttons, toolbar buttons -- transparent |
| `.btn-danger` | Destructive actions (delete, remove) -- red tint |
| `.btn-icon` | Icon-only buttons -- compact padding |
| `.btn-sm` / `.btn-lg` | Size modifiers |

Never use bare `<button>` without a `.btn` class. The old global `button {}` selector
is removed -- unstyled buttons will look broken.

## 5. Transitions and Animations

### Spring easing is the default

All `transition:` declarations should use the spring easing curve. `cubic-bezier` is
reserved exclusively for `@keyframes` animation timing functions that depend on it.

The spring curves are defined as CSS custom properties in `globals.css`:
- `--ease-spring` -- short spring, for most transitions
- `--ease-spring-smooth` -- long spring, for slow/dramatic transitions (0.8s+)
- `--ease-standard` -- `cubic-bezier(0.4, 0, 0.2, 1)`, only for keyframe animations

### Duration inventory (do not change existing durations)

| Duration | Usage |
|---|---|
| `0.12s` | Micro-interactions (button color changes, badge flashes) |
| `0.15s` | Standard UI feedback (hovers, focus, toggles) -- most common |
| `0.2s` | Button transforms, opacity fades |
| `0.25s` | Sidebar, cards, inputs, nav pills |
| `0.28s` | Sidebar width expansion |
| `0.3s` | Progress bars, spotlight entrance |
| `0.8s` | Source-card hover (dramatic, card lift) |
| `1s` | Compact-header stuck transition (slow, ambient) |

### Keyframes

Global keyframes are in `utilities.css`. Available: `spin`, `shimmer`,
`pulse`, `reranker-slide`, `ahaFlash`, `ahaPulse`, `progress-ring-ping`,
`kb-slide-right`, `kb-ctx-in`, `ss-text-bounce-in`, `ss-icon-bounce`, `ss-close-bounce`.

**CSS Module keyframes**: Vite's CSS Modules auto-scope keyframe names. If a
module references a keyframe defined in `utilities.css`, the scoped name won't
match the global one. Define component-specific keyframes **inside the module
file itself** (prefix with the component's abbreviation, e.g. `is-icon-bounce`
for InnerSidebar). Only add to `utilities.css` if the keyframe is shared across
multiple unrelated components.

### Animation safety

When an element plays a move/entrance animation, suppress hover effects on it
and its siblings until the animation completes. Use CSS `:not()` to gate hover
rules and `:has()` to suppress sibling hovers:

```css
/* Pill hover only when not animating */
.nav:has(.active:hover) .pill:not(.pillDown):not(.pillUp) {
  scale: 1.06;
}

/* Suppress sibling hovers during pill animation */
.nav:has(.pillDown) .navBtn:hover,
.nav:has(.pillUp) .navBtn:hover {
  transform: none;
}
```

For JS-driven animation safety, use `onAnimationEnd` to reset state and
re-enable interactions.

### Tab / segmented control — TabSwitcher component

**`TabSwitcher` (`components/ui/TabSwitcher.jsx`) is the ONLY horizontal tab
switching component in the app.** Every tab bar, segmented control, or filter
toggle must use it. Never build inline tab switchers with raw buttons, manual
pill divs, or CSS-only active indicators. The component handles pill animation,
sizing, layout, and active state internally via `usePillAnimation`.

**Basic usage:**

```jsx
import TabSwitcher from '../components/ui/TabSwitcher';

<TabSwitcher
  tabs={[
    { key: 'steps',  label: 'Steps' },
    { key: 'routes', label: 'Routes' },
  ]}
  activeKey={activeTab}
  onChange={setActiveTab}
/>
```

**Key props:**

- `width` — number (fixed px), `'100%'` (fill parent), or omit (auto/fit).
- `equalTabs` — tabs divide the available/container width equally (each
  `flex:1`); implies `width:100%` unless `width` is set. Tab widths depend on
  the CONTAINER.
- `uniformTabs` — **opt-in.** Measures the widest tab's natural (content) width
  and gives every tab that width, so all tabs look even. Unlike `equalTabs`,
  the widths depend on CONTENT (the longest tab) and the switcher stays
  fit-content (does not stretch to `100%`). Re-measures when tab
  labels/icons/badges or `size` change. Default `false`; don't combine with
  `equalTabs`.
- `size` — `'sm'` | `'md'` (default) | `'lg'` for preset font/padding, plus
  `'toolbar'`: sm content metrics at a fixed 32px height with Dropdown-matched
  radii (10px track / 7px pill instead of the capsule). Use `'toolbar'` whenever
  a TabSwitcher sits in a PageToolbar-style control row next to search inputs,
  Dropdowns, and `.btn` actions (all 32px tall) so heights and corner rounding
  line up.
- `pillVariant` — `'filled'` (default, gradient with glass sheen), `'subtle'`
  (bg-tertiary), or `'accent'` (solid accent-primary). Use the default
  `'filled'` variant unless the user explicitly requests a different style.
- `showTrack` — `true` adds a bg-tertiary track behind tabs (Dropdown style).
- `borderBottom` — `true` adds a bottom border separator.
- `animationVariant` — `'full'` (default, stretch+squish) or `'stretch'`.
- `multipliers` — override `usePillAnimation` multipliers (defaults are
  already tuned for compact tabs).
- `enabled` — pass `false` for portaled content not yet mounted.
- Tabs support `icon` (MDI path), `badge` (count), and `disabled`.
- `renderTab` — custom render function `(tab, { isActive }) => Node`.

**Icon-only mode:**

When ALL tabs have `icon` but no `label`, TabSwitcher automatically shows the
active tab's name below the tab bar with a pop-out/pop-in animation on switch.
The label text comes from `tab.tooltip` (falls back to `tab.key`). If the active
tab has a `color`, the label uses it.

The label is absolutely positioned below the component (`top: 100%`) and does not
affect the component's box size. Consumers should add padding or margin on the
parent element if the label would overlap content below (typically ~16px clearance).

Do NOT manually render a label below an icon-only TabSwitcher — the component
handles it natively. Just provide `tooltip` on each tab.

```jsx
<TabSwitcher
  tabs={[
    { key: 'health', icon: mdiHeartPulse, tooltip: 'Health', color: '#10B981' },
    { key: 'chat',   icon: mdiChat,       tooltip: 'Chat',   color: '#3B82F6' },
  ]}
  activeKey={activeTab}
  onChange={setActiveTab}
  size="sm"
  width="100%"
/>
```

**When to use raw `usePillAnimation` instead:**

Only for vertical nav pills (e.g. the main Sidebar). All horizontal tab/segment
patterns must go through `TabSwitcher`.

**Animation calibration notes:**

The component defaults to compact multipliers and a
`cubic-bezier(0.34, 1.4, 0.64, 1)` transition (gentle bounce). The `1.4`
control point overshoot scale:
- `1.0` = no overshoot (standard ease-out)
- `1.4` = gentle bounce (TabSwitcher default)
- `1.56` = `var(--ease-spring)` (sidebar pills only, too aggressive for tabs)
- `2.0+` = never use

**Reference implementations:**
- `NodePalette.jsx` — Steps/Routes toggle (subtle pill, borderBottom)
- `Dropdown.jsx` — filter groups (filled pill, showTrack, sm size)

### Performance: individual transform properties

Prefer individual CSS transform properties (`translate`, `scale`, `rotate`)
over the combined `transform` shorthand when an element animates multiple
transform types independently (e.g. position + scale). Individual properties:
- Can be transitioned/animated independently without conflicts
- Each promotes to its own compositor track
- Pair with `will-change: translate, scale` targeting only what animates

```css
/* Prefer this -- translate and scale animate independently */
.pill {
  translate: 0 var(--y);
  will-change: translate, scale;
  transition: translate 0.35s ease-out, scale 0.2s var(--ease-spring);
}

/* Over this -- one transform property, can't independently animate parts */
.pill {
  transform: translateY(var(--y)) scale(1);
}
```

Use `contain: layout style` on scroll containers that host animated children
to isolate layout recalculations.

### Optimizing animations and transitions

Not all CSS properties are equal in cost. Prefer properties that the browser
can composite on the GPU without triggering layout or paint:

| Tier | Properties | Cost |
|---|---|---|
| Composite-only (prefer) | `translate`, `scale`, `rotate`, `opacity` | GPU, no layout/paint |
| Paint-only (acceptable) | `background-color`, `box-shadow`, `border-color` | Repaint, no layout |
| Layout-triggering (avoid animating) | `top`, `left`, `width`, `height`, `padding`, `margin` | Full layout + paint |

**Rules:**
- Position elements with `translate` instead of `top`/`left` when animating.
  `top`/`left` trigger layout every frame; `translate` is purely composited.
- Use `scale` instead of animating `width`/`height` when possible.
- **Do not transition `color`** -- text and icon color changes should be
  instant. The visual difference during a color transition is negligible and
  it forces the browser to repaint glyphs/SVG paths every frame. Just set the
  new color directly. This applies to both text elements and icon `color`/`fill`
  on hover.
- **Do not transition `opacity`** -- almost no UI pattern in this project
  benefits from fading. Elements either appear or don't. Conditional rendering
  (`{visible && <El />}`) is preferred over opacity toggling. Exception:
  overlays and backdrops that must visually fade in/out.
- When multiple properties need transitioning, only list the ones that are
  actually animated -- don't include static properties in `transition`
  declarations as a "just in case".

## 6. Search Bar

**All search inputs with a magnifying-glass icon must use the `.search-bar` utility
classes** defined in `utilities.css`. Never build inline search bars with manual
`position: absolute` icons, custom wrappers, or one-off CSS modules for search inputs.

The key design: the `scale(1.005)` hover transform lives on the wrapper so the icon,
input, and clear button all scale together.

**Basic usage:**

```jsx
import Icon from '../components/ui/Icon'
import { mdiMagnify } from '@mdi/js'

<div className="search-bar">
  <span className="search-bar-icon"><Icon path={mdiMagnify} size={0.65} /></span>
  <input
    type="text"
    placeholder="Search..."
    value={search}
    onChange={e => setSearch(e.target.value)}
    className="search-bar-input"
  />
</div>
```

**With a clear button:**

```jsx
<div className="search-bar">
  <span className="search-bar-icon"><Icon path={mdiMagnify} size={0.6} /></span>
  <input className="search-bar-input" value={q} onChange={e => setQ(e.target.value)}
    placeholder="Search..." style={{ paddingRight: 28 }} />
  {q && (
    <span className="search-bar-clear">
      <button className="btn btn-ghost btn-icon" onClick={() => setQ('')} style={{ padding: 2 }}>
        <Icon path={mdiClose} size={0.5} />
      </button>
    </span>
  )}
</div>
```

**Classes:**

| Class | Element | Purpose |
|---|---|---|
| `.search-bar` | `<div>` wrapper | Relative positioning, spring hover scale |
| `.search-bar-icon` | `<span>` around Icon | Absolute left, vertically centered, `z-index: 1` |
| `.search-bar-input` | `<input>` | Glass input (backdrop-filter, border, box-shadow, focus ring) |
| `.search-bar-clear` | `<span>` around clear button | Absolute right, vertically centered |

**Size control:** Pages control size via inline `style` on the input. The defaults are
`font-size: 13px` and `padding: 8px 12px 8px 34px`. For compact search bars (e.g. inside
panels), override padding, font-size, background, and backdrop-filter as needed.

**What NOT to do:**

- Do not use `.sys-input` or `.search-input` for new search inputs -- use `.search-bar-input`
- Do not use inline SVGs for the search icon -- use `<Icon path={mdiMagnify} />`
- Do not put `transform: scale()` on the input itself -- it goes on `.search-bar`
- Do not use `position: absolute` + `transform: translateY(-50%)` for the icon manually --
  `.search-bar-icon` handles this with `translate: 0 -50%`
- Do not build flex-based icon+input wrappers -- use the absolute-positioned icon pattern
- Specialized embedded searches (Dropdown internal, SpotlightSearch, InnerSidebar) keep
  their own styling -- the `.search-bar` system is for page-level and panel-level inputs

## 7. Shared Components

### InnerSidebar (`components/ui/InnerSidebar.jsx`)

Use this for ALL page-level navigation sidebars. Do not create one-off sidebar
components. See the JSDoc at the top of the file for full API documentation.

Basic usage:
```jsx
const SECTIONS = [
  { id: 'general', label: 'General', icon: mdiCog, color: '#3B82F6', keywords: ['general'] },
]
<InnerSidebar sections={SECTIONS} activeId={active} onNavigate={setActive} />
```

Supports: search, group headers, badges, route-based nav, custom item rendering,
header/footer slots, configurable dimensions.

**Layout rules (critical):**

1. **Always wrap InnerSidebar in a fixed-width container** so page content never
   shifts when the sidebar expands on hover. The sidebar overlays on top of
   surrounding content instead of pushing it. Every page that uses InnerSidebar
   must use this wrapper:
   ```jsx
   <div style={{ flexShrink: 0, width: 52, overflow: 'visible', alignSelf: 'stretch' }}>
     <InnerSidebar sections={SECTIONS} activeId={active} onNavigate={setActive} />
   </div>
   ```
   Without this wrapper, hovering the sidebar will cause the entire page layout
   to reflow. See SettingsPage and ContactsPage for reference implementations.

2. **InnerSidebar is sticky by default** -- it uses `position: sticky` internally
   (via the `sticky` prop, which defaults to `true`). Never override this. The
   sidebar should stay in place while the page scrolls. Do not set
   `position: relative` or `position: static` on it or its wrapper.

### Modal (`components/ui/Modal.jsx`)

Use for all modal dialogs. Do not build inline modal overlays with `position: fixed`.

```jsx
<Modal open={show} onClose={() => setShow(false)} title="Edit Item" icon={mdiPencil}>
  <p>Content</p>
  <div className="modal-actions">
    <button className="btn cancel-btn" onClick={close}>Cancel</button>
    <button className="btn confirm-btn" onClick={save}>Save</button>
  </div>
</Modal>
```

### ConfirmDialog (`components/ui/ConfirmDialog.jsx`)

Use for all confirmation prompts. Do not build inline confirm modals.

```jsx
<ConfirmDialog
  title="Delete source?"
  description="This is permanent."
  highlightText={source.name}
  danger
  confirmLabel="Delete"
  onConfirm={handleDelete}
  onCancel={() => setOpen(false)}
/>
```

### Dropdown (`components/ui/Dropdown.jsx`)

Use for **all** dropdown selects -- both single-select and multi-select. Do not
build inline dropdown menus with custom `position: absolute` panels, `<select>`
elements, or checkbox lists. This component handles both modes natively.

```jsx
<Dropdown
  options={[
    { value: 'all', label: 'All' },
    { value: 'live', label: 'Live', color: '#10B981' },
    { value: 'draft', label: 'Draft', color: '#F59E0B' },
  ]}
  value={filter}
  onChange={setFilter}
/>
```

**Multi-select mode** -- pass `multiple` to enable. The menu stays open on
selection, items show checkmarks, the trigger displays a summary label, and
a clear button appears when items are selected:

```jsx
<Dropdown
  multiple
  options={[
    { value: 'billing', label: 'Billing' },
    { value: 'support', label: 'Support' },
    { value: 'sales',   label: 'Sales' },
  ]}
  value={selectedDepts}
  onChange={setSelectedDepts}
  placeholder="Select departments..."
/>
```

In multi-select mode:
- `value` is an array of selected values
- `onChange` receives the updated array on every toggle
- Trigger shows comma-joined labels (up to 2), then "N selected"
- `multiLabel` -- `(selectedOptions) => string` to customize the trigger label
- A clear (x) button on the trigger resets to `[]`
- Footer defaults to "Click items to toggle selection" (override with `footer`)

**Appearance props:**
- `size` -- 'sm' | 'md' | 'lg' preset sizes (default: 'md')
- `triggerStyle` -- inline styles merged onto the trigger for custom
  padding/fontSize/width. Overrides size preset values.
- `menuStyle` -- inline styles merged onto the menu panel
- `color` -- accent color (hex) for the trigger gradient and active item
- `variant` -- 'default' (gradient with glass sheen) or 'ghost' (transparent
  with subtle border, for toolbars or compact areas)

**Positioning props:**
- `align` -- 'start' | 'center' | 'end' menu alignment (default: 'center')
- `menuWidth` -- 'trigger' (match trigger width) | 'auto' (natural content
  width, min = trigger) | number (fixed px). Default: 'auto'.

**Search and filter props:**
- `searchable` -- adds a search/filter input at the top of the menu
- `searchPlaceholder` -- placeholder for the search input
- `filterGroups` -- array of `{ value, label, filter? }` objects for a
  segmented control bar (uses TabSwitcher internally). Combines with search.

**Behavior props:**
- `renderOption` -- `(option, { isActive }) => ReactNode` for custom item
  rendering (icons, badges, rich content). Also renders the trigger label.
- `disabled` -- disables the trigger button
- `footer` -- string or ReactNode for info bar at menu bottom. Multi-select
  shows a default hint; pass `false` to suppress.

**Per-option fields:**
- `color` -- accent color (hex). Active item and trigger use this color.
- `icon` -- MDI icon path. Renders in both menu items and trigger.
- `desc` -- secondary description text below the label (menu items only).
- `disabled` -- greyed out and unselectable.

**Behavioral notes:**
- Menu is portaled to document.body (never clipped by overflow:hidden parents)
- Portal is wrapped in a themed container so CSS variables work correctly
- Menu tracks trigger position on scroll via requestAnimationFrame
- Menu auto-closes when trigger scrolls out of viewport
- Menu flips above when less than 120px of space below the trigger
- Menu is clamped to viewport edges (8px margin)
- Trigger has the same gradient-shift hover effect as primary buttons
- Menu items have spring hover animation (translateY + scale)

### Checkbox (`components/ui/Checkbox.jsx`)

**This is the ONLY checkbox in the app.** Every checkbox -- form toggles, "select
all", multi-select rows, settings switches -- must use it. **Never** use a raw
`<input type="checkbox">` (with or without `accentColor`): the native control
renders inconsistently across the 20 themes and ignores the design system. This
was a widespread miss across the app; if you find a raw checkbox, replace it.

It matches the multi-select Dropdown's `.dd-check` look exactly: a 16px rounded
box with a `1.5px var(--border-hover)` border that fills with `var(--btn-gradient)`
and shows a white `mdiCheck` when on (`mdiMinus` when indeterminate). It is a
drop-in replacement -- an overlaid transparent native input keeps it
keyboard-accessible and lets it sit inside a `<label>` so clicking the label text
still toggles it.

```jsx
import Checkbox from '../components/ui/Checkbox'

<Checkbox checked={enabled} onChange={setEnabled} />
```

**Critical API difference from a raw input:** `onChange` receives the **new
boolean value**, not an event. Migrate `onChange={e => setX(e.target.checked)}`
to `onChange={v => setX(v)}` (or just `onChange={setX}`).

**Props:**

| Prop | Type | Purpose |
|---|---|---|
| `checked` | boolean | Current state |
| `onChange` | `(checked: boolean) => void` | Receives the new boolean, **not** an event |
| `disabled` | boolean | Disables the control |
| `indeterminate` | boolean | Tri-state (e.g. partial "select all") -- shows a minus |
| `className` | string | Forwarded to the box |
| `data-tooltip` | string | Forwarded to the box |

**What NOT to do:**

- Do not use `<input type="checkbox">` with `style={{ accentColor: ... }}`
- Do not build custom checkbox `<div>`s or SVG check marks -- use this component
- Do not read `e.target.checked` in the handler -- `onChange` already gives the boolean

### ContextMenu (`components/ui/ContextMenu.jsx`)

**This is the ONLY context menu component in the app.** Do not create inline context
menus, local ContextMenu functions, or new context menu components. Every right-click
menu and action menu must use this component. See the comprehensive JSDoc at the top
of the file for full API documentation, item shapes, and examples.

```jsx
import ContextMenu from '../components/ui/ContextMenu'

const [ctx, setCtx] = useState(null);

<div onContextMenu={e => {
  e.preventDefault();
  setCtx({ x: e.clientX, y: e.clientY });
}}>
  Right-click me
</div>

<ContextMenu
  open={!!ctx}
  x={ctx?.x}
  y={ctx?.y}
  onClose={() => setCtx(null)}
  items={[
    { icon: mdiPencil, label: 'Rename', onClick: handleRename },
    { separator: true },
    { icon: mdiTrashCanOutline, label: 'Delete', danger: true, onClick: handleDelete },
  ]}
/>
```

**Supported item types:** standard items (icon, label, shortcut, onClick, disabled,
danger, color), separators (`{ separator: true }`), group headers
(`{ header: 'Title' }`), custom render slots (`{ render: (onClose) => <JSX /> }`).

**Features:** portaled to document.body with theme propagation, viewport clamping,
entrance animation, keyboard navigation (ArrowDown/Up, Enter, Escape), click-outside
dismiss, spring hover on items, auto-flip when near bottom edge.

### Icon (`components/ui/Icon.jsx`)

Wrapper around `@mdi/react`. Always import icons from `@mdi/js`:
```jsx
import Icon from '../components/ui/Icon'
import { mdiCog } from '@mdi/js'
<Icon path={mdiCog} size={0.8} color="var(--text-primary)" />
```

## 8. Page Layout

Every top-level page component must use the global `.page` class on its root element:

```jsx
export default function MyPage() {
  return (
    <div className="page">
      <div className="page-header">
        <h2>Page Title</h2>
        <p>Optional subtitle</p>
      </div>
      {/* page content */}
    </div>
  )
}
```

The `.page` class (defined in `App.css`) provides `padding: 32px 40px` and
`width: 100%` with no max-width constraint -- pages expand to fill the
available space within the sidebar layout.

**Rules:**
- Always use `className="page"` on the root div -- never create a local CSS Module
  class that sets its own padding, max-width, or margin for page-level layout.
- Use `className="page-header"` for the title area. It provides consistent
  bottom margin.
- Pages that need a sidebar use `InnerSidebar` alongside the content area, both
  wrapped in the `.page` div with `display: flex` and `gap: 20`. The InnerSidebar
  must be inside a fixed-width wrapper (see InnerSidebar layout rules above):
  ```jsx
  <div className="page" style={{ display: 'flex', gap: 20, padding: '32px 28px 32px 16px', alignItems: 'flex-start' }}>
    <div style={{ flexShrink: 0, width: 52, overflow: 'visible', alignSelf: 'stretch' }}>
      <InnerSidebar ... />
    </div>
    <div style={{ flex: 1, minWidth: 0 }}>
      {/* page content */}
    </div>
  </div>
  ```
- For fixed-height pages (like Chat), add a secondary class:
  `className="page chat-page"`.
- Do not set `max-width` or `margin: 0 auto` on page containers -- the layout
  should always fill the available width.

## 9. Component Patterns

### Dynamic states -- prefer data attributes over ternary styles

```jsx
// Prefer this:
<div className={s.indicator} data-active={isActive || undefined}>
// CSS: .indicator[data-active] { background: var(--accent-primary); }

// Over this:
<div style={{ background: isActive ? 'var(--accent-primary)' : 'transparent' }}>
```

### Frosted card pattern

Many components use the same frosted glass card look. Use these properties:
```css
background: var(--bg-secondary);
border: 1px solid var(--border-surface);
border-radius: 14px;
backdrop-filter: blur(15px);
-webkit-backdrop-filter: blur(15px);
box-shadow: var(--card-shadow);
```

### Color references

Always use CSS custom properties for colors, never hardcode:
- Text: `var(--text-primary)`, `var(--text-secondary)`, `var(--text-tertiary)`, `var(--text-muted)`
- Backgrounds: `var(--bg-primary)`, `var(--bg-secondary)`, `var(--bg-tertiary)`
- Borders: `var(--border-color)`, `var(--border-hover)`, `var(--border-surface)`
- Accents: `var(--accent-primary)`, `var(--accent-secondary)`, `var(--accent-tertiary)`

Exception: component-specific accent colors (like section colors in sidebars) can be
hardcoded hex since they don't change with themes.

## 10. CSS Refactor Status

The CSS architecture overhaul is in progress. See `docs/css/CSS_REFACTOR_CONTINUE.md` for
current status and what remains.

**Remaining phases:**
- Phase 5 remainder + Phase 6: Page CSS Module migration (convert inline styles to modules)
- Phase 7: Design tokens (blur variables, z-index scale, transition easing tokens)
- Phase 8: Write `docs/CSS_ARCHITECTURE.md` -- the final authoritative reference

When working on any page or component, opportunistically convert inline styles to CSS
Module classes where practical. Do not add new inline styles for static values.

## 11. Tooltips -- use `data-tooltip`, never `title`

The app has a custom global tooltip system (`components/ui/Tooltip.jsx`) that renders
styled, themed tooltips. **Native browser `title` attributes must never be used on HTML
elements** -- they produce unstyled, unthemed browser-default tooltips that conflict with
the custom system.

**Rules:**
- Use `data-tooltip="text"` on any HTML element that needs a hover tooltip.
- **Never use `title="text"` on native HTML elements** (`<button>`, `<div>`, `<span>`,
  `<a>`, `<Link>`, etc.). This is the single most important tooltip rule.
- `title` is still used as a **React component prop** on components like `Modal`,
  `ConfirmDialog`, `Section`, `CardHeader`, etc. -- those are prop names, not HTML
  attributes. Do not change those.
- If you create a wrapper component that accepts a `title` prop and renders it on a
  native element, render it as `data-tooltip={title}` on the underlying element.
- A `MutationObserver` in the tooltip system auto-converts stray `title` attributes to
  `data-tooltip` at runtime as a safety net, but do not rely on it -- always use
  `data-tooltip` in source code.

```jsx
// Correct:
<button className="btn btn-ghost" data-tooltip="Delete item" onClick={onDelete}>

// Wrong -- produces native browser tooltip:
<button className="btn btn-ghost" title="Delete item" onClick={onDelete}>

// Component props are fine -- these are not HTML attributes:
<Modal title="Edit Item" open={show} onClose={close}>
<ConfirmDialog title="Delete source?" onConfirm={handleDelete} />
```

## 12. List-Item Hover -- scale + contrast, never border

All clickable text rows -- dropdown items, folder/article lists, menu options, attention
lists, search results, file lists, session actions, and any similar clickable text -- must
use the **flyout nav hover pattern**: subtle enlarge + text contrast boost. **Never add or
change a border on hover for text list items.**

### The pattern

- **Base state**: `color: var(--text-secondary)` or `var(--text-tertiary)`
- **Hover**: `transform: scale(...)` + `color: var(--text-primary)` -- item subtly
  enlarges and text brightens
- **Active (pressed)**: `transform: scale(0.98)` -- press-in feedback
- **Transition**: `transform 0.25s var(--ease-spring), color 0.15s ease`
- **No border change**, no `background` change, no `box-shadow` change on hover

### Scale sizing

The scale factor depends on the width of the element. Wider items need a subtler
scale to avoid looking jarring:

| Element width | Scale | Class / example |
|---|---|---|
| Narrow (dropdown items, sidebar nav, KB tree items) | `1.02` | `.list-item-hover` |
| Wide (full-width rows, attention lists, table rows) | `1.01` | `.list-item-hover-subtle` |

Use your judgement: if the item spans most of the viewport width, use `1.01`.

### Global utility classes

Two classes are available in `utilities.css`:

```jsx
// Narrow items (dropdown options, tree nav, compact menus)
<div className="list-item-hover" onClick={handleClick}>Item text</div>

// Wide items (full-width rows, attention lists)
<div className="list-item-hover-subtle" onClick={handleClick}>Wide row</div>
```

Both provide the correct transition, hover scale, active press, and text contrast.
Use them on any clickable text row that doesn't already have the pattern via its own
CSS Module.

### When NOT to apply

- **Cards** (with background, border, shadow, and visual container) -- cards use a
  different hover language (border glow, subtle lift, shadow change). The CardList `.card`
  and `.row` components are cards, not text list items.
- **Buttons** -- buttons have their own hover system via `.btn` variants.
- **Tabs and pills** -- these are navigation chrome, not list items.
- **Links in prose** -- inline text links use opacity change, not scale.

### What to check

When building any list of clickable items, verify:
1. Hover uses `scale(1.02)` or `scale(1.01)` + `color: var(--text-primary)`, not `border-color` change
2. Scale factor matches element width: `1.02` for narrow, `1.01` for wide
3. Active uses `scale(0.98)`
4. Transition uses `var(--ease-spring)` for the transform
5. No `background-color` change on hover (exceptions: selected/active state indicators)

## 12. General Frontend Rules

- **React 19** with Vite 7, SWC for JSX transforms.
- **No TypeScript** -- the project uses plain JSX.
- **Dhivehi/RTL support** exists via `.dhivehi-text` and `[lang="dv"]` classes.
- **Auth**: global fetch interceptor in `main.jsx` attaches Bearer tokens automatically.
  Use `useAuth()` hook for `authFetch` when you need explicit control.
- **API service**: `services/apiService.js` provides typed API methods. Prefer it over
  raw `fetch` for backend calls.
- **Notifications**: use `showNotification(message, type, duration)` passed as prop.
  Types: `'info'`, `'success'`, `'error'`, `'warning'`.

## 13. Permission-aware UI -- never let users hit a 403

If the backend gates an action behind a permission (`middleware.RequirePermission(...)`),
the UI must **never present that action as available and then fail with a 403**. The user
should either not see the option, or see it clearly unavailable with the reason. The
backend check is the security boundary; the UI mirror is a UX affordance -- **always keep
both** (defense in depth). Never use the UI gate as the only check.

### The three primitives

1. **`utils/permissions.js`** -- the single source of truth for permission keys + friendly
   labels (`PERMISSION_GROUPS`, `permissionLabel`, `permissionReason`). When you add a new
   `can_*` permission to the backend roles table, add it here too so the role editor and
   every gate can render a human reason. Do **not** duplicate this catalog anywhere.
2. **`usePermissions()` hook** (`hooks/usePermissions.js`) -- reads the current user's
   `role.permissions` map. Returns `{ can, cannotReason, missing, permissions }`:
   ```jsx
   const { can, cannotReason } = usePermissions();
   can('can_publish_knowledge')                    // boolean
   can(['can_edit_knowledge', 'can_publish_knowledge'])         // all (AND)
   can(['can_access_inbox', 'can_view_all_chats'], 'any')       // any (OR)
   cannotReason(['can_edit_knowledge', 'can_publish_knowledge'])  // reason for ONLY the
                                                   // permission(s) the user is missing
   ```
3. **`<PermissionButton>`** (`components/ui/PermissionButton.jsx`) -- a `<button>` that is
   disabled (or hidden) when the user lacks the permission, with the reason as a tooltip.
   Use it for plain `.btn` actions:
   ```jsx
   <PermissionButton
     permission="can_publish_knowledge"      // string or string[] (+ optional mode="any")
     className="btn btn-primary"
     onClick={handlePublish}
     disabled={publishing}                    // other, non-permission disable reasons
     data-tooltip="Publish to the chatbot"    // tooltip shown when allowed
   >
     Publish
   </PermissionButton>
   ```

### Hide vs. disable -- be selective and smart (do what most apps do)

There is no single rule; choose per case the way a well-built app would:

- **Disable + explain** individual primary actions the user is otherwise looking at
  (Publish, Save, a toggle, Unpublish). They stay visible so the feature is discoverable
  and the user learns what access to request. Show the reason via `data-tooltip` and add
  `data-no-permission=""` for styling. For bespoke buttons with their own inline disabled
  styling, fold the permission into the existing `disabled` + tooltip rather than forcing
  `<PermissionButton>`.
- **Hide** whole pages/sections/nav the role cannot use at all, and secondary entry points
  in menus, toolbars, and bulk bars (context-menu "Delete", a toolbar "+ New", a bulk
  "Publish"). There's nowhere good to attach a reason and showing them is just noise --
  conditionally omit the item (e.g. `...(canEdit ? [item] : [])`, or pass `undefined` to a
  handler prop that the component already guards on, like `TreeNavigation`'s `onNewItem`).
- Match the gate to the backend exactly. If a route requires two permissions
  (`editKB, pubKB`), gate on **both** (`can(['can_edit_knowledge','can_publish_knowledge'])`);
  `cannotReason` will name only the one the user actually lacks.

### Checklist when adding a permission-gated action

1. Does the backend route have `RequirePermission`? Mirror the **exact** key(s) in the UI.
2. Primary, discoverable action → disable + `data-tooltip` reason. Secondary/menu/nav entry
   → hide.
3. Reason text comes from `cannotReason(...)` / `permissionLabel(...)`, never hardcoded.
4. New `can_*` key → add it to `utils/permissions.js` (and the backend roles table /
   `setup_database.py`).
5. Never remove the backend check -- the UI gate is UX only.
