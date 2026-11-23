# Translating Bloom

## Contents

- [Welcome](#welcome)
- [Getting started](#getting-started)
- [The rules](#the-rules)
- [When your language goes live](#when-your-language-goes-live)
- [Testing your translation](#testing-your-translation)
- [Reviewers](#reviewers)
- [Something's wrong](#somethings-wrong)
- [License](#license)

## Welcome

Bloom is an org-mode notes app for Android. Thanks for helping people use it in their own language. All translation happens on Weblate in your browser; you don't need git or Android tools.

## Getting started

1. Open **https://hosted.weblate.org/engage/bloom/** and sign in (GitHub, GitLab or email).
2. Pick your language. If it isn't listed, click "Start new translation". Unsure whether to start one? Open a "Request a new language" issue on GitHub first.
3. Turn on notifications so you hear about new strings: in Weblate, open your profile Settings > Notifications and watch the Bloom project.
4. Read the **Glossary** for your language before you start. Agree on key terms with other translators of your language there, not string by string.

## The rules

1. **Never translate org-mode syntax.** `TODO`, `DONE`, `SCHEDULED`, `DEADLINE`, `CLOSED`, `ID`, `CUSTOM_ID`, `:PROPERTIES:` and capture placeholders such as `%date`, `%cursor`, `%^{Title}` stay exactly as written. Bloom writes them into `.org` files that Emacs must understand. Weblate highlights them as untranslatable glossary terms.
2. **Never translate the name "Bloom"**, or proper names like Emacs, Syncthing, Orgzly, org-roam, or theme names (Catppuccin, Tokyo Night...).
3. **Keep placeholders intact.** `%1$s` (text) and `%1$d` (number) must appear in your translation just as in English. You **may reorder** them to fit your grammar: `%2$s … %1$s` is fine. Weblate won't accept a translation with a broken placeholder.
4. **Fill in every plural form** Weblate shows you. Your language may have more forms than English ("one", "few", "many", "other"); each needs its own text, even when some look alike.
5. **Keep markup and escapes** that appear in the English text (`\n`, `<b>`). Weblate handles XML escaping of apostrophes and quotes for you.
6. **Read the context.** The note shown with a string (and an attached screenshot, when there is one) explains where it appears and what each placeholder holds. If a string is still ambiguous, ask in that string's Comments tab; the maintainer is notified.
7. **Mind the length.** Buttons, chips, tabs and widget text have little room. If your language runs long, prefer a shorter natural word over a literal one. Store listing fields have hard limits (title 30, short description 80 characters); Weblate shows a counter.
8. **Tone.** Bloom's English is short, plain and friendly, in sentence case ("Save changes?", not "Save Changes?"). Use your own language's typographic conventions (quotation marks, spacing before punctuation in French, and so on). Choose formal or informal "you" once for your language, record it in the glossary, and stay consistent.
9. **Don't paste machine translation unchecked.** Suggestions are a starting point. Only submit text you'd be happy to read in the app.
10. **Partial is fine.** Untranslated strings show in English. Start with the most visible areas: the `common` and navigation components, then the editor and capture.

## When your language goes live

- Your translations reach the code through a pull request that Weblate opens; the maintainer merges it, usually weekly and always before a release.
- A language ships in Bloom once its **core screens** (navigation, notebooks, editor, capture, search, agenda, widgets) are about 95% translated. Settings and help text can follow later. Until then your work is kept and ships the moment it crosses the line.
- Once shipped, Android 13 and newer list it under Settings > System > Languages > App languages > Bloom. On older Android versions, Bloom follows the phone's language.
- Every translator is credited in git history, and in the release notes of the version where their language first ships.

## Testing your translation

1. Open the latest Weblate pull request on GitHub: https://github.com/rrajath/bloom/pulls (author "weblate").
2. Open its "Checks" tab, then the build run, then download the `app-debug` artifact (you need a free GitHub account). Install the APK. It installs **next to** the regular Bloom app as a separate debug app with its own empty notes folder, so it won't touch your notes.
3. Switch the debug app to your language. On Android 13 or newer: Android Settings > System > Languages > App languages > Bloom Debug, then pick your language. On older Android versions, change the phone's language (Settings > System > Languages). With a computer and `adb`, this also works: `adb shell cmd locale set-app-locales com.rrajath.grove.debug --locales de` (use your language code).
4. Look for text that's cut off, overlapping, odd in context, or still English. Fix it in Weblate. If the problem is the app's layout rather than your text, leave a comment on the string.

## Reviewers

Once a language has several active translators, the maintainer may make an experienced one a **reviewer**. New translations in that language are then approved by the reviewer before they ship. If you'd like to review your language, say so in the Translations discussion on GitHub.

## Something's wrong

- A bad translation in a released version: fix it in Weblate, or open a GitHub issue with the `translations` label and a screenshot.
- English source text that's unclear, wrong, or impossible to translate well (for example a sentence assembled from pieces): comment on the string in Weblate, starting with "Source string issue". The maintainer fixes the English.

## License

Translations are contributed under the project's license, **Apache-2.0**, the same as the code.
