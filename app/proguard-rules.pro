# OkHttp & Okio membawa aturan sendiri; ini hanya membungkam peringatan untuk dependensi opsional yang tidak dipakai.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JGit memuat teks lewat refleksi (JGitText) dan banyak kelas opsional (SSH, JGSS, management).
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn com.jcraft.**
-dontwarn org.ietf.jgss.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn com.googlecode.javaewah.**
-dontwarn org.slf4j.**
