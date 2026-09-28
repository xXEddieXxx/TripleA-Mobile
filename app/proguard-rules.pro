# The engine uses reflection (attachment properties by name, Lombok generated accessors) and
# Java serialization for save games; keep it exactly as compiled.
-keep class games.strategy.** { *; }
-keep class org.triplea.** { *; }
-keepclassmembers class games.strategy.** { *; }
-keepclassmembers class org.triplea.** { *; }
-keepnames class games.strategy.** { *; }
-keepnames class org.triplea.** { *; }
-keep class com.ctc.wstx.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }
-keep class org.snakeyaml.engine.** { *; }
-keep class com.google.gson.** { *; }

# Save games are Java serialized and contain Guava collections next to the engine classes.
# Every serializable class must keep its name and its serialized shape: SafeObjectInputStream
# allows classes by name (an obfuscated "al0" is refused), and a save must load in every build,
# debug or release, on both players' phones.
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepnames class com.google.common.collect.** { *; }
-keepnames class com.google.common.base.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn jakarta.xml.bind.**
-dontwarn java.beans.**
# optional integrations of Woodstox that are not on Android (OSGi, bnd, MSV)
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.**
-dontwarn com.ctc.wstx.shaded.msv_core.driver.textui.**
