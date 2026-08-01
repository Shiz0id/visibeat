#!/usr/bin/env python3
"""Reads the embedding table out of a pulled VisiBeat database and checks whether
the vectors mean anything.

Why this exists
---------------
The audio preprocessing has to match what the model was trained on, and a
mismatch is silent: the tensor is the right shape, the numbers are the right
magnitude, the vector normalises, the cosine computes, the radio plays. Nothing
throws. The usual way to check is to reproduce one spectrogram in the model
author's own Python and compare — which needs their environment.

This is the cheaper check, and it needs nothing but the database, because the
database already contains ground truth: **which tracks are on the same album.**

Tracks from one album share production, instrumentation, mastering, usually key
and tempo. Any working audio encoder must place them closer together than two
tracks picked at random. If it does, the pipeline is carrying real acoustic
information end to end. If same-album pairs score no better than random ones,
the embeddings are noise no matter how healthy the norms look.

Usage:  python tools/radio/inspect_vectors.py dbdump/music-pim-db
"""

import sys
import sqlite3
import struct
import random
import numpy as np

RANDOM_PAIRS = 200_000
SEED = 20260731


def load(path):
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row

    tables = {r[0] for r in con.execute(
        "SELECT name FROM sqlite_master WHERE type='table'")}
    if "track_embeddings" not in tables:
        sys.exit("No track_embeddings table — is this the right database?")

    rows = list(con.execute("""
        SELECT e.trackId, e.modelId, e.dim, e.vector, e.artistId, e.albumId,
               r.effectiveTitle       AS title,
               r.effectiveArtistDisplay AS artist,
               r.effectiveAlbumTitle  AS album
        FROM track_embeddings e
        LEFT JOIN resolved_tracks r ON r.trackId = e.trackId
    """))
    total_tracks = con.execute("SELECT COUNT(*) FROM tracks").fetchone()[0]
    con.close()
    return rows, total_tracks


def main(path):
    rows, total_tracks = load(path)
    if not rows:
        sys.exit("track_embeddings is empty — nothing has been analysed yet.")

    print("=" * 72)
    print("INVENTORY")
    print("=" * 72)

    models = {}
    for r in rows:
        models.setdefault((r["modelId"], r["dim"]), [0, 0])
        models[(r["modelId"], r["dim"])][0] += 1
        if len(r["vector"]) == 0:
            models[(r["modelId"], r["dim"])][1] += 1

    for (model, dim), (n, dead) in sorted(models.items()):
        print(f"  {model}")
        print(f"    dim {dim} · {n} rows · {n - dead} vectors · {dead} unreadable")
    print(f"  library: {total_tracks} tracks")

    if len(models) > 1:
        print("\n  !! more than one model present. Vectors from different models")
        print("     share no space; the index should hold exactly one.")

    # Keep only real vectors of the dominant model.
    (model, dim), _ = max(models.items(), key=lambda kv: kv[1][0])
    good = [r for r in rows
            if r["modelId"] == model and r["dim"] == dim
            and len(r["vector"]) == dim * 4]
    if len(good) < 10:
        sys.exit(f"\nOnly {len(good)} usable vectors — let indexing run further.")

    V = np.array(
        [struct.unpack(f"<{dim}f", r["vector"]) for r in good], dtype=np.float64)
    album = np.array([r["albumId"] if r["albumId"] is not None else -1 for r in good])
    artist = np.array([r["artistId"] if r["artistId"] is not None else -1 for r in good])

    # ------------------------------------------------------------------ norms
    print()
    print("=" * 72)
    print("NORMS   (stored vectors are L2-normalised, so every one should be 1.0)")
    print("=" * 72)
    norms = np.linalg.norm(V, axis=1)
    print(f"  min {norms.min():.6f}   mean {norms.mean():.6f}   max {norms.max():.6f}")
    off = int(np.sum(np.abs(norms - 1.0) > 1e-3))
    if off:
        print(f"  !! {off} vectors are not unit length. The BLOB round trip or")
        print("     the normalisation is wrong, and every cosine is affected.")
    else:
        print("  OK — the pack/normalise/unpack path is intact.")

    dead_dims = int(np.sum(np.all(np.abs(V) < 1e-8, axis=0)))
    if dead_dims:
        print(f"  note: {dead_dims} of {dim} dimensions are zero for every track.")

    # ---------------------------------------------------------- distributions
    rng = random.Random(SEED)
    n = len(good)
    idx_a = np.array([rng.randrange(n) for _ in range(RANDOM_PAIRS)])
    idx_b = np.array([rng.randrange(n) for _ in range(RANDOM_PAIRS)])
    keep = idx_a != idx_b
    idx_a, idx_b = idx_a[keep], idx_b[keep]
    rand_sim = np.einsum("ij,ij->i", V[idx_a], V[idx_b])

    print()
    print("=" * 72)
    print("SIMILARITY SPREAD   (random pairs)")
    print("=" * 72)
    qs = np.percentile(rand_sim, [1, 25, 50, 75, 99])
    print(f"  mean {rand_sim.mean():+.4f}   sd {rand_sim.std():.4f}")
    print("  p1 {:+.3f}   p25 {:+.3f}   p50 {:+.3f}   p75 {:+.3f}   p99 {:+.3f}"
          .format(*qs))

    if rand_sim.std() < 0.01:
        print("  !! almost no spread. Every track looks like every other track,")
        print("     which is what a collapsed embedding space looks like.")
    if rand_sim.mean() > 0.95:
        print("  !! random pairs are near-identical. Suspect the model is being")
        print("     fed something constant — check the spectrogram parameters.")

    near_dupe = float(np.mean(rand_sim > 0.999))
    if near_dupe > 0.01:
        print(f"  !! {near_dupe:.1%} of random pairs are effectively identical.")
        print("     Usually a cluster of failed decodes embedded as silence.")

    # ----------------------------------------------- the test that matters
    print()
    print("=" * 72)
    print("STRUCTURE   (does the space know what an album is?)")
    print("=" * 72)

    def within_group(labels, name):
        sims = []
        by = {}
        for i, g in enumerate(labels):
            if g >= 0:
                by.setdefault(g, []).append(i)
        for g, members in by.items():
            if len(members) < 2:
                continue
            m = V[members]
            gram = m @ m.T
            iu = np.triu_indices(len(members), k=1)
            sims.append(gram[iu])
        if not sims:
            print(f"  {name}: not enough grouped tracks yet.")
            return None
        allsims = np.concatenate(sims)
        print(f"  {name:<14} mean {allsims.mean():+.4f}  "
              f"over {len(allsims)} pairs in {len(by)} groups")
        return allsims

    same_album = within_group(album, "same album")
    same_artist = within_group(artist, "same artist")
    print(f"  {'random':<14} mean {rand_sim.mean():+.4f}")

    print()
    if same_album is not None:
        gap = same_album.mean() - rand_sim.mean()
        pooled = np.sqrt((same_album.std() ** 2 + rand_sim.std() ** 2) / 2)
        d = gap / pooled if pooled > 0 else 0.0
        print(f"  album lift: {gap:+.4f}   (effect size d = {d:.2f})")
        if d > 0.8:
            print("  STRONG. Same-album tracks are clearly closer than random ones,")
            print("  so the pipeline is carrying real acoustic information and the")
            print("  preprocessing is very unlikely to be mismatched.")
        elif d > 0.3:
            print("  PRESENT but modest. Probably working; worth re-checking once")
            print("  more of the library is indexed.")
        else:
            print("  ABSENT. The encoder cannot tell an album from two unrelated")
            print("  tracks, which is what a mel-config mismatch looks like.")
            print("  Compare MelConfig against the model's published parameters.")

    # -------------------------------------------------------------- eyeball
    print()
    print("=" * 72)
    print("NEAREST NEIGHBOURS   (the human check — do these belong together?)")
    print("=" * 72)
    rng2 = random.Random(SEED)
    for i in rng2.sample(range(n), min(4, n)):
        sims = V @ V[i]
        sims[i] = -2
        top = np.argsort(sims)[::-1][:4]
        seed = good[i]
        print(f"\n  {seed['title'] or '?'} — {seed['artist'] or '?'}")
        for j in top:
            r = good[j]
            print(f"      {sims[j]:+.3f}  {r['title'] or '?'} — {r['artist'] or '?'}"
                  f"  [{r['album'] or '?'}]")
    print()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
