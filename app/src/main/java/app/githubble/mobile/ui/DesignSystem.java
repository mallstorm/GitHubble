package app.githubble.mobile.ui;

/** Shared spacing and corner scale so every screen uses the same flat, compact card language. */
public final class DesignSystem {
  public static final int RADIUS_CARD = 12;
  public static final int RADIUS_CONTROL = 10;
  public static final int GAP = 8;
  public static final int GAP_MIN = 6;

  private DesignSystem() {}

  /**
   * Maps a requested corner radius (dp) onto the scale: small marks stay as they are, controls and
   * icon badges use 10, cards 12; values from 24 up are pills or circles and are kept.
   */
  public static int radius(int requested) {
    if (requested >= 24 || requested <= 8) return requested;
    return requested <= 13 ? RADIUS_CONTROL : RADIUS_CARD;
  }
}
