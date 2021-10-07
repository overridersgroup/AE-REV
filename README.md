# About

**Ancient Empires - Revolutions** is an evolved and maintained version of the game **Ancient Empires Reloaded** on Android platform. New features are listed below.
* New & renewed units
* New abilities

My [website](https://fabate.altervista.org/)

# License

The game's code is under GNU General Public License version 3. You can use the assets of this game without any permission or restriction.

# Contribute a new language support

Follow the steps below to contribute a new language support:

1. Fork the project to your repository.
2. Install [Github Desktop](https://desktop.github.com/) and check out the forked project.
3. Find your country's locale ID from [JDK 6 and JRE 6 Supported Locales](http://www.oracle.com/technetwork/java/javase/locales-137662.html).
4. Grab a TrueType font which supports your country's language, put it under `project_aeii/android/assets/fonts/`. We recommend that you rename the font's filename to your country's locale ID.
5. Go to `project_aeii/android/assets/lang/`, duplicate `en_US.dat` file, rename it to your country's locale ID and translate all the texts after `=`. Note that for the first line, just change `en_US.ttf` to the font filename you are using.
6. Save the language file in UTF-8 format and you are done. Submit a pull request and we will add you to credits once your changes are merged.
