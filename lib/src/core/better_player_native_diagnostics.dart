import 'dart:async';

/// One diagnostic record sent by the native player (Android only): DRM licence
/// requests, network load errors, decoder setup, device/Widevine capabilities
/// and snapshots taken while the player is stuck buffering.
class BetterPlayerNativeDiagnosticEvent {
  BetterPlayerNativeDiagnosticEvent({
    required this.textureId,
    required this.name,
    required this.data,
    this.nativeTimeMs,
  });

  /// Texture of the player that sent the event, to tell players apart.
  final int? textureId;
  final String name;
  final Map<String, Object?> data;

  /// Native `SystemClock.elapsedRealtime()` when the event was created.
  final int? nativeTimeMs;

  @override
  String toString() => 'BetterPlayerNativeDiagnosticEvent($name, $data)';
}

/// Broadcast stream of [BetterPlayerNativeDiagnosticEvent]s from every player.
/// Nothing is stored here; listeners decide what to keep.
class BetterPlayerNativeDiagnostics {
  BetterPlayerNativeDiagnostics._();

  static final StreamController<BetterPlayerNativeDiagnosticEvent>
      _controller =
      StreamController<BetterPlayerNativeDiagnosticEvent>.broadcast();

  static Stream<BetterPlayerNativeDiagnosticEvent> get stream =>
      _controller.stream;

  static void add(BetterPlayerNativeDiagnosticEvent event) {
    if (_controller.hasListener) _controller.add(event);
  }

  static Map<String, Object?> normalize(Object? raw) {
    if (raw is! Map) return const {};
    return raw.map((key, value) => MapEntry(key.toString(), _value(value)));
  }

  static Object? _value(Object? value) {
    if (value is Map) return normalize(value);
    if (value is List) return value.map(_value).toList();
    return value;
  }
}
