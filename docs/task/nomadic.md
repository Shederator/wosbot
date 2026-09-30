# Nomadic Merchant

The shop is a fixed 3×2 grid on the 720×1280 viewport. Pass one takes every
non-VIP card whose price strip has no gem (natural-resource price, including
accelerators and teleports). Pass two buys each VIP product icon with the
existing gem sheet taps. Free Refresh runs after both passes.

VIP is identified by the yellow product icon, not by a gem on the price.
Tapping the price strip does not buy; the tap is the product body.

Gem-price template `gemprice.png` was cropped from a 19-gem VIP card on
2026-09-30. Search is limited to each slot's price strip so the wallet gem is
ignored. Threshold 80.

Confirmation of a resource take is a per-slot product-region mean channel
change of 12 after the reward flyout (2.5–4 s). An unchanged slot is skipped
and the scan continues.
