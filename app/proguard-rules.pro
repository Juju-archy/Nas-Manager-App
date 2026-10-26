# Gson matches JSON keys against field names via reflection; only fields annotated with
# @SerializedName are protected by Gson's own bundled rules (see its META-INF/proguard/gson.pro),
# so every DTO/request class in DashboardRepository.kt must be kept as-is, annotated or not,
# otherwise R8 renaming silently breaks (de)serialization instead of crashing.
-keep class com.nasmanagerapp.data.dashboard.*Dto { *; }
-keep class com.nasmanagerapp.data.dashboard.*Request { *; }
