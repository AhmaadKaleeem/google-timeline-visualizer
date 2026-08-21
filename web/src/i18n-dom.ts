import type { I18n, PluralKey, StringKey, TextKey } from './i18n';

/**
 * Applies the catalog to every annotated node under `root` in a single pass.
 *
 * `data-i18n="key"` replaces the element's textContent, so it may only sit on an element whose
 * content is plain text. `data-i18n-count="10"` marks that key as a plural key and supplies the
 * count. `data-i18n-attr="attr:key;attr:key"` sets attributes; keys match /^[a-z][A-Za-z0-9]*$/
 * so neither ';' nor ':' can ever appear inside one and the split is unambiguous. On an
 * <input> this deliberately writes the `value` content attribute rather than the property:
 * the browser mirrors it into the field only while the dirty value flag is unset, so a
 * language switch relabels an untouched default and never overwrites what the user typed.
 *
 * Idempotent: every value is written from the catalog and nothing is read back, so calling it
 * again after a language change is the only code path a switch needs. Pass `document` rather
 * than `document.body` or the <title> and <meta> elements in the head are missed.
 *
 * This module is the only part of the i18n layer that touches the DOM. i18n.ts stays DOM-free
 * so it can be tested under the node environment without pulling in jsdom.
 */
export function applyStrings(root: ParentNode, i18n: I18n): void {
  for (const node of root.querySelectorAll<HTMLElement>('[data-i18n]')) {
    const key = node.dataset.i18n as StringKey;
    const rawCount = node.dataset.i18nCount;
    node.textContent = rawCount === undefined
      ? i18n.t(key as TextKey)
      : i18n.t(key as PluralKey, { count: Number(rawCount) });
  }
  for (const node of root.querySelectorAll<HTMLElement>('[data-i18n-attr]')) {
    for (const pair of (node.dataset.i18nAttr ?? '').split(';')) {
      const [attribute, key] = pair.split(':');
      if (attribute && key) node.setAttribute(attribute, i18n.t(key as TextKey));
    }
  }
}

/**
 * The full tag, never a bare language: VoiceOver picks its speech synthesiser from it, and Han
 * unification means the browser picks a different glyph for the same code point under zh-CN
 * than under ja or zh-TW, so a wrong value is a legibility bug rather than a cosmetic one.
 *
 * The exported MP4 is out of reach of this: canvas text takes its font only from context.font,
 * so CJK glyph selection there follows the system language, not the app language.
 */
export function syncDocumentLang(i18n: I18n): void {
  document.documentElement.lang = i18n.locale;
}
