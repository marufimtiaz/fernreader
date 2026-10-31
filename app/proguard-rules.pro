# kotlinx.serialization: keep generated serializers of bridge/backup models
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.epubreader.** { *** Companion; }
-keepclasseswithmembers class app.epubreader.** { kotlinx.serialization.KSerializer serializer(...); }
