# Workers are created by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
