# Meta Spatial SDK: registra sistemas y componentes por clase y usa
# reflexión. Si R8 fusiona u ofusca sus clases, dos sistemas distintos
# terminan siendo la misma clase y el SDK falla al arrancar con
# "already registered ... Duplicate Systems are not allowed".
-keep class com.meta.spatial.** { *; }
-keep interface com.meta.spatial.** { *; }
-dontwarn com.meta.spatial.**

# El tablero 3D se abre por nombre desde PanelBus.
-keep class com.sesgaro.control_angulos.GameActivity3D { *; }
