# Reglas de R8 para EpubFold.

# El código de la app se conserva entero: usa nombres de clase en el manifiesto, widgets,
# servicios y algo de reflexión; lo que se reduce es el código no usado de las librerías.
-keep class com.emmanuel.epubfold.** { *; }

# Informes de fallos legibles (Crashlytics)
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception

# junrar (CBR) referencia clases opcionales que no se incluyen
-keep class com.github.junrar.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.apache.commons.vfs2.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn kotlinx.coroutines.debug.**

# SDK de Anthropic: usa Jackson con reflexión sobre sus modelos
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,KotlinMetadata
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
