# AppForge Build

Aplikasi kecil untuk **mengirim proyek ke GitHub, build lewat GitHub Actions, lalu memasang APK-nya**.
Tidak ada editor di dalamnya: edit kode di editor lain (mis. Acode) pada folder proyek.

## Alur
1. Buat proyek dari template, atau impor dari GitHub/ZIP. Folder proyek ada di lokasi yang dipilih di Pengaturan
   (bisa memori internal, kartu SD, atau folder bebas) sehingga bisa dibuka editor lain.
2. Tekan **Build**: file yang berubah dikirim ke repo, workflow dijalankan. Tampilannya daftar langkah seperti GitHub Actions
   (✓ / ✗ / spinner / – per langkah, durasi, waktu berjalan); ketuk langkah untuk membuka lognya, langkah yang gagal terbuka otomatis. Notifikasi juga muncul.
   Tekan lama untuk memilih varian (debug/release, APK/AAB).
   Build terus berjalan walau kamu keluar dari proyek. Di daftar proyek muncul spanduk "Sedang di-build: …" (langkah, waktu, progres)
   dan baris proyeknya juga menampilkan progres; ketuk untuk kembali melihat langkah dan lognya. Setelah selesai, spanduk menampilkan hasil
   terakhir sampai proyeknya dibuka.
   Saat mengirim file, tampil "Mengirim 3/12 file · 340 KB / 2.1 MB" (bar progres mengikuti ukuran), di layar proyek maupun di spanduk daftar proyek.
   Log mencatat dulu "Berubah: N dari M file, total X", lalu tiap file yang terkirim.
   **Beberapa proyek bisa di-build bersamaan** (satu build per proyek, tiap proyek punya repo sendiri). Spanduk menampilkan
   "N proyek sedang di-build" (ketuk untuk memilih), notifikasi merangkum semuanya dengan tombol "Batalkan semua", dan tiap proyek
   bisa dibatalkan sendiri dari layarnya. Batas dari GitHub: akun gratis maksimal 20 job bersamaan; permintaan API dibatasi 5000/jam,
   jadi interval cek status diperlambat otomatis kalau build lebih dari satu.
3. Berhasil: tombol **Install**. Gagal: log dan daftar **Error** (file:baris, ketuk untuk menyalin lokasi).
4. Menu ⋮: riwayat build, pengaturan build (versi, release, keystore sendiri), **Hapus proyek**.

Setiap langkah bisa dibuka (termasuk Set up job, Post…, Complete job, dan tahap kirim file); langkah yang belum mulai menampilkan "Belum dimulai".
Log berbaris-nomor per langkah; yang tampil 300 baris terakhir, sisanya dimuat bertahap (supaya ringan). `##[error]` merah, `##[warning]` kuning.
Pemecahan log per langkah memakai cap waktu tiap baris log dan started_at tiap langkah dari API GitHub (bukan menebak dari teks).
Log langkah yang sedang berjalan diambil lewat polling (tiap ~5 detik) dan bergantung pada apa yang sudah disediakan API GitHub, jadi bisa tertinggal dari tampilan di aplikasi GitHub.

Header langkah yang terbuka menempel di atas layar saat lognya digulung (ketuk untuk menutup langkah itu).
Menu ⋮: Buka di GitHub (halaman run), Buka/Tutup semua langkah.

## Hapus
Pilih salah satu atau keduanya: folder di perangkat dan/atau repo di GitHub.
Repo hanya dihapus kalau dibuat oleh AppForge (ditandai di deskripsinya), dan token butuh scope **delete_repo**.
Repo dihapus lebih dulu; kalau gagal, folder lokal tidak ikut dihapus.

## Teknis
Tanpa Compose dan tanpa Sora: hanya AppCompat, Material, RecyclerView, OkHttp, coroutines. Kotlin 1.9.24, AGP 8.5.2, Gradle 8.9.
Logika build/GitHub/penyimpanan dipakai ulang dari versi sebelumnya.

## Impor folder, visibilitas, rilis
- **Impor folder** (menu di layar utama): pilih folder lewat pemilih folder Android, isinya disalin jadi proyek baru (build/, .git, .gradle dilewati). Repo GitHub dibuat otomatis saat Build pertama.
- **Visibilitas repo**: Pengaturan build > "Repo publik di GitHub". Berlaku saat build berikutnya dan langsung disamakan kalau repo tetapnya sudah ada.
- **Rilis GitHub** (Android/Flutter): Pengaturan build > pilih "Buat rilis baru" atau "Perbarui rilis yang ada (sesuai tag)". Rilis dibuat oleh runner GitHub Actions sendiri (`gh release`), jadi APK tidak diunggah ulang dari HP.
- **Log build**: grup log (`##[group]`) dilipat seperti di halaman GitHub Actions; ketuk judulnya untuk membuka.

## Cara kirim file (seperti Termux)
Build memakai JGit: proyek dikemas jadi satu paket git terkompresi lalu di-`push` ke repo AppForge (force push; hanya repo buatan AppForge).
File dibaca sebagai aliran dari disk, tidak dimuat ke RAM. Repo git lokal disimpan di `filesDir/git` dan dipakai ulang supaya build berikutnya hanya mengirim yang baru.
Folder `build/`, `.git`, `.gradle`, `.idea` dilewati. Batas GitHub: 100 MB per file. Impor/hubungkan repo milik user tetap lewat REST API (tanpa force push).
Progres fase "Writing objects" dihitung per objek git (blob+tree+commit), bukan per file; saat satu file besar dikirim, angkanya bisa diam sebentar.

## Catatan jujur
- Perubahan JGit belum dikompilasi maupun dijalankan di perangkat. Kalau ada error build atau crash saat push, kirim lognya.
- Belum pernah dikompilasi (tidak ada Android SDK di lingkungan pembuatnya); hanya dicek sintaks. Kirim log Actions kalau ada error.
- Token GitHub: scope `repo` + `workflow`, plus `delete_repo` kalau mau hapus repo.
