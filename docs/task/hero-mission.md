# Hero's Mission

On 720×1280 event screens, the orange reward-progress track runs beneath the
chests. Its interior near y=1044 is orange through x=645 at the final 10 reward;
at progress 3 and 4, orange ends near x=280 and x=333, with grey track to the
right. Classify a continuous HSV-orange line from x=185 to x=645, allowing a
small interruption. An entirely grey track represents zero progress; this
state has only synthetic test coverage until a saved zero-progress frame is
available. A missing or irregular track is unknown, not completion.
The saved complete frame originated on a Pixel 9 at 1080×2424; scale it to
720×1616 and crop the bottom 720×1280 before comparing coordinates.

Navigation and unknown progress each have a three-visit budget per profile.
The last failure timestamp is stored in UTC and compared with the game's 00:00
UTC daily reset, including after a bot restart. An unreadable bar does not prove
that the event is complete or that a rally was sent.
