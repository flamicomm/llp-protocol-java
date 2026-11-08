# AGENTS.md — llp-protocol-java

Implementación **Java 21** del wire LLP v4 y de su perfil R1 (canal de comandos
confiable). Consume el corpus de conformidad de `llp-spec`; **no define el wire**.

**Este archivo es normativo**: solo invariantes y comandos. API, arquitectura y
migración: `README.md`.

## Comandos

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

mvn test                              # suite JUnit 5 + corpus de conformidad
mvn verify                            # + javadoc, jar de fuentes y cobertura JaCoCo
tools/sync-vectors.sh                 # re-sincronizar el corpus desde ../llp-spec
```

`tools/sync-vectors.sh [ruta-a-llp-spec]` copia `vectors/*.json` y
`MANIFEST.sha256` a `src/test/resources/llp-vectors/` y verifica el manifiesto.
Correr `mvn test` después: `VectorManifestTest` vuelve a verificar cada archivo.

## Invariantes

- **JDK 21** (`maven.compiler.release=21`) y **JUnit 5**.
- **Sin dependencias de runtime**: el core no depende de nada. Jackson está en
  scope `test`, solo para leer los vectores.
- **El corpus es vendorizado y no se edita a mano.** Se actualiza con
  `tools/sync-vectors.sh` desde `llp-spec`; una copia parcial o modificada falla
  el build por el manifiesto.
- **El wire cambia primero en `llp-spec`** (spec y vectores); acá se implementa
  después.
- **Camino caliente sin asignación por byte**: `FeedResult` usa singletons,
  `payload()` es una vista read-only y `LLPFrame` copia una sola vez al entregar.
- **Tiempo inyectado**: el parser recibe `nowMs` o un clock; ningún test duerme
  (el tiempo se simula con `dt_ms`).
- **Errores de wire son eventos** (`LLPError`), no excepciones. `LLPException`
  es solo para uso incorrecto de la API (payload > 255, canal fuera de rango,
  configuración inválida).
- **v4 rompe compatibilidad con 3.x**; no hay modo dual.
- **Sin datos volátiles** en docs: el total de casos lo imprime `mvn test`.

## Documentación

| archivo | contenido |
|---|---|
| `README.md` | API, trama, canales, perfil R1, conformidad y migración 3.x→4 |
| `pom.xml` | versión, release de Java y dependencias de test |
| `src/test/resources/llp-vectors/` | corpus vendorizado + `MANIFEST.sha256` |

Norma: `llp-spec/docs/SPEC-v4.md` (wire) y `llp-spec/docs/RELIABILITY.md`
(perfil R1).

## Git

- `dev` y `main` son **ramas protegidas**: solo PR, nunca commits directos.
- Ramas `feature/<scope>-<slug>`, `fix/<slug>`, `docs/<slug>`, `hotfix/<slug>`.
- Un PR = un tema; squash a `dev`; release `dev → main` con tag `vX.Y.Z`.
- Commits Conventional Commits **en español**; código, nombres y Javadoc en
  inglés (regla del repo).
- `mvn verify` verde antes de pedir review.
