# ADR 0002: Product name "Garnacha Boy"

- Status: Accepted
- Date: 2026-07-14

## Decision

The product is named **Garnacha Boy**, with the permanent applicationId
`com.trebuchetdynamics.garnacha` (changed from `com.trebuchetdynamics.mgba`
before any public release, while doing so is still free).

## Why

The name follows the Pizza Boy convention — a concrete, playful food noun that
says nothing about emulation and is therefore memorable. "Garnacha" is
distinctive, unclaimed in this category, and local to where the product is
built.

## The accepted risk

The "Boy" suffix evokes Nintendo's **Game Boy** trademark. On Google Play a
trademark complaint is cheap to file and acted on quickly, and Nintendo has
recently pursued emulator projects aggressively (Yuzu and Citra shut down in
2024; Dolphin's Steam release was blocked). Pizza Boy itself survives as a
sideloaded APK and is no longer on the Play Store.

This risk was presented explicitly and accepted by the product owner on
2026-07-14. The exposure is understood to be:

- **Google Play:** a listing takedown is plausible. This is the channel at risk.
- **GitHub Releases and F-Droid:** effectively unaffected; these are not
  trademark-complaint-driven channels.

Mitigations in place:

- The app is never described as being, or endorsed by, mGBA or Nintendo.
- Store copy will credit mGBA as the emulation core under MPL-2.0.
- No Nintendo trademark, logo, or product likeness is used in branding.

## Reversal

Dropping the suffix to plain "Garnacha" removes the exposure and requires
changing only `strings.xml`, store metadata, and this ADR — the applicationId,
signing key, and save data are unaffected, because the display name and the
applicationId are independent. **This remains cheap to reverse right up until
the Play listing is submitted (M6); it is expensive afterwards.**

The applicationId itself is *not* reversible after v0.1.0 ships: changing it
orphans every install and its save data, and Play never permits it at all.
