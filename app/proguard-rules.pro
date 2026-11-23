# kotlinx.serialization: keep generated serializers of bridge/backup models
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.fernreader.** { *** Companion; }
-keepclasseswithmembers class app.fernreader.** { kotlinx.serialization.KSerializer serializer(...); }
