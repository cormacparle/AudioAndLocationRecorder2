AudioAndLocationRecorder2
=========================


This is an android app written by Cormac Parle for [Bat Conservation Ireland](http://www.batconservationireland.org), based on a fork of [Hertz, the WAV recorder](https://github.com/ucam-cl-dtg/hertz), originally written by Rhodri Karim.

This project is licenced under [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0)

One of the ways Bat Conservation Ireland monitors bat populations is via car surveys. Volunteers drive along pre-defined routes at specified times of the year with a bat detector (a device that transforms bats' ultrasonic sonar into human-audible sounds) plugged into a recording device. The recordings are later analysed to determine which species are present, and the species records undergo statistical processing to determine whether populations are rising, falling, or stable.

This app allows an android phone to be used as the recording device. The volunteer enters a 3-character code at the beginning of their transect, and the device records both the audio from the bat detector (to a .wav file) and its own position (to a .csv file).

The app also monitors the audio signal, and reports any problem due to the audio connection or the signal from the detector.

Android version 2.2

Contributing
------------
1. Fork it
1. Make your changes
1. Submit a pull request

