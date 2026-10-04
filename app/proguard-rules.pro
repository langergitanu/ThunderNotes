# ThunderNotes ProGuard / R8 rules
#
# Release builds are currently configured with isMinifyEnabled = false,
# so this file is dormant. When we flip minification on (likely phase 4+),
# add keep rules for:
#
#   - Room generated classes (already covered by androidx.room.Keep annotations
#     + the standard consumer-rules that ship with Room)
#   - kotlinx.serialization @Serializable serializers
#       -keep class com.thundernotes.**$$serializer { *; }
#       -keepclassmembers class com.thundernotes.** {
#           *** Companion;
#         }
#       -keepclasseswithmembers class com.thundernotes.** {
#           kotlinx.serialization.KSerializer serializer(...);
#         }
#   - OkHttp (consumer rules already ship with the library)
#   - AndroidX Ink (TBD)
#   - Protobuf generated classes (TBD)
#   - OpenCV native bindings (TBD)
#   - Pdfium native bindings (TBD)
#   - Reflection-based plugin loaders (Plugin API, phase 5+)
#
# Until then this file is intentionally minimal.
