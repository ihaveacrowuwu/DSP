/**
 * The icon set — deliberately this short.
 *
 * This interface is text-first: an icon earns its place only where a label
 * cannot fit (the collapsed nav rail) or where the glyph is genuinely faster to
 * read than words (a chevron, a tick, a calendar). Everything else is a word,
 * because "Correct to bleached" cannot be drawn.
 *
 * Re-exporting through one module rather than importing from '@mdi/js' at each
 * call site keeps that budget visible: adding an icon means adding a line here,
 * which is a decision rather than an accident. All paths are Material Design
 * Icons, per the frontend guideline — no second icon library, ever.
 */
export {
  // navigation rail
  mdiMapOutline as iconMap,
  mdiClipboardCheckOutline as iconQueue,
  mdiFormatListBulletedSquare as iconRecords,
  mdiTuneVariant as iconOperations,
  mdiLogoutVariant as iconSignOut,
  // controls
  mdiChevronDown as iconChevronDown,
  mdiChevronLeft as iconChevronLeft,
  mdiChevronRight as iconChevronRight,
  mdiCheck as iconCheck,
  mdiMinus as iconDash,
  mdiClose as iconClose,
  mdiCalendarBlankOutline as iconCalendar,
  mdiArrowLeft as iconBack,
  // appearance
  mdiWeatherNight as iconNight,
  mdiWeatherSunny as iconDay,
} from '@mdi/js'
