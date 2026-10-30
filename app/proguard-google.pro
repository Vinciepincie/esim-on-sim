# google build only.
# ML Kit (QR scanner) instantiates its component registrars by reflection from manifest
# metadata; R8 full mode otherwise strips their no-arg constructors (NoSuchMethodException).
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
