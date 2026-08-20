# kotlinx.serialization keeps its generated serializers via @Serializable; R8 needs to
# be told the companion-object serializer holders are reachable.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit interfaces are implemented reflectively at runtime.
-keep,allowobfuscation interface mv.muraka.core.network.** { *; }

# Room generates implementations that reference entity constructors.
-keep class mv.muraka.core.database.** { *; }
