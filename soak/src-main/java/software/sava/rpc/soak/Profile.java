package software.sava.rpc.soak;

import java.util.Locale;

/// The run shapes the harness supports. A profile is only a table of defaults: every knob it
/// sets can still be overridden in `run.env` or the environment, and the resolved value — not
/// the profile — is what `run.env` records and the report reads.
///
/// `control` and `live` deliberately have no third column of their own. A control run is a
/// 90–120 s probe of one deliberately broken thing, so it wants the `validate` timings; a live
/// run is a bounded observation against somebody else's node, so it wants the `pilot` timings
/// with its own required `SOAK_LIVE_*` keys on top. Inventing separate tables for them would be
/// two more sets of numbers nobody measured.
public enum Profile {

  VALIDATE,
  PILOT,
  CAMPAIGN,
  CONTROL,
  LIVE;

  /// The `SOAK_PROFILE` spelling: lower case, as written in `run.env` and in the run directory
  /// name.
  public String lowerName() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Profile of(final String value) {
    final var trimmed = value == null ? "" : value.trim();
    for (final var profile : values()) {
      if (profile.lowerName().equalsIgnoreCase(trimmed)) {
        return profile;
      }
    }
    throw new IllegalArgumentException("SOAK_PROFILE must be one of validate|pilot|campaign|control|live, not '"
        + trimmed + '\'');
  }

  /// Picks this profile's column out of a validate/pilot/campaign default triple, which is how
  /// every table in the design is written.
  public <T> T pick(final T validate, final T pilot, final T campaign) {
    return switch (this) {
      case VALIDATE, CONTROL -> validate;
      case PILOT, LIVE -> pilot;
      case CAMPAIGN -> campaign;
    };
  }

  /// True for the profiles that drive a real fault schedule against the controlled peers. A
  /// `live` run has no peer to inject faults, and a `control` run replaces the schedule with the
  /// single control fault named by `SOAK_CONTROL`.
  public boolean scheduledFaults() {
    return this == VALIDATE || this == PILOT || this == CAMPAIGN;
  }
}
