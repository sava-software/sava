package software.sava.core.crypto.ed25519;

import org.bouncycastle.math.ec.rfc7748.X25519Field;
import org.bouncycastle.math.raw.Interleave;
import software.sava.core.crypto.Hash;

import java.security.DigestException;
import java.security.MessageDigest;

public final class Ed25519Util {

  private static final long[] D = new long[]{
      0x78a3, 0x1359, 0x4dca, 0x75eb,
      0xd8ab, 0x4141, 0x0a4d, 0x0070,
      0xe898, 0x7779, 0x4079, 0x8cc7,
      0xfe73, 0x2b6f, 0x6cee, 0x5203
  };

  private static final long[] I = new long[]{
      0xa0b0, 0x4a0e, 0x1b27, 0xc4ee,
      0xe478, 0xad2f, 0x1806, 0x2f43,
      0xd7a7, 0x3dfb, 0x0099, 0x2b4d,
      0xdf0b, 0x4fc1, 0x2480, 0x2b83
  };

  private static final long[] gf1 = new long[16];

  private static final int[] B_x = new int[]{52811034, 25909283, 8072341, 50637101, 13785486, 30858332, 20483199, 20966410, 43936626, 4379245};
  private static final int[] B_y = new int[]{40265304, 26843545, 6710886, 53687091, 13421772, 40265318, 26843545, 6710886, 53687091, 13421772};
  private static final int[] B128_x = new int[]{12052516, 1174424, 4087752, 38672185, 20040971, 21899680, 55468344, 20105554, 66708015, 9981791};
  private static final int[] B128_y = new int[]{66430571, 45040722, 4842939, 15895846, 18981244, 46308410, 4697481, 8903007, 53646190, 12474675};

  private static final int[] C_d2 = new int[]{45281625, 27714825, 18181821, 13898781, 114729, 49533232, 60832955, 30306712, 48412415, 4722099};
  private static final int[] C_d4 = new int[]{23454386, 55429651, 2809210, 27797563, 229458, 31957600, 54557047, 27058993, 29715967, 9444199};

  private static final int[] PRECOMP_BASE_COMB;

  private static int[] X25519FieldOne() {
    final var field = X25519Field.create();
    field[0] = 1;
    return field;
  }

  private record PointAccum(int[] x, int[] y, int[] z, int[] u, int[] v) {

    private static PointAccum create() {
      return new PointAccum(X25519Field.create(), X25519Field.create(), X25519Field.create(), X25519Field.create(), X25519Field.create());
    }

    private static PointAccum pointSetNeutral() {
      return new PointAccum(X25519Field.create(), X25519FieldOne(), X25519FieldOne(), X25519Field.create(), X25519FieldOne());
    }
  }

  private record PointExtended(int[] x, int[] y, int[] z, int[] t) {

    private static PointExtended create() {
      return new PointExtended(X25519Field.create(), X25519Field.create(), X25519Field.create(), X25519Field.create());
    }
  }

  private record PointTemp(int[] r0, int[] r1) {

    private static PointTemp create() {
      return new PointTemp(X25519Field.create(), X25519Field.create());
    }
  }

  private record PointAffine(int[] x, int[] y) {

    private static PointAffine create() {
      return new PointAffine(X25519Field.create(), X25519Field.create());
    }
  }

  private record PointPrecomp(int[] ymx_h,
                              int[] ypx_h,
                              int[] xyd) {

    private static PointPrecomp create() {
      return new PointPrecomp(X25519Field.create(), X25519Field.create(), X25519Field.create());
    }
  }

  private static void pointCopy(final PointAccum var0, final PointExtended var1) {
    X25519Field.copy(var0.x, 0, var1.x, 0);
    X25519Field.copy(var0.y, 0, var1.y, 0);
    X25519Field.copy(var0.z, 0, var1.z, 0);
    X25519Field.mul(var0.u, var0.v, var1.t);
  }

  private static void pointCopy(final PointAffine var0, final PointExtended var1) {
    X25519Field.copy(var0.x, 0, var1.x, 0);
    X25519Field.copy(var0.y, 0, var1.y, 0);
    X25519Field.one(var1.z);
    X25519Field.mul(var0.x, var0.y, var1.t);
  }

  private static void pointAdd(final PointExtended var0, final PointExtended var1, final PointExtended var2, final PointTemp var3) {
    final int[] var4 = var2.x;
    final int[] var5 = var2.y;
    final int[] var6 = var3.r0;
    final int[] var7 = var3.r1;
    X25519Field.apm(var0.y, var0.x, var5, var4);
    X25519Field.apm(var1.y, var1.x, var7, var6);
    X25519Field.mul(var4, var6, var4);
    X25519Field.mul(var5, var7, var5);
    X25519Field.mul(var0.t, var1.t, var6);
    X25519Field.mul(var6, C_d2, var6);
    X25519Field.add(var0.z, var0.z, var7);
    X25519Field.mul(var7, var1.z, var7);
    X25519Field.apm(var5, var4, var5, var4);
    X25519Field.apm(var7, var6, var7, var6);
    X25519Field.mul(var4, var5, var2.t);
    X25519Field.mul(var6, var7, var2.z);
    X25519Field.mul(var4, var6, var2.x);
    X25519Field.mul(var5, var7, var2.y);
  }

  private static void pointPrecompute(final PointAffine var0, PointExtended[] var1, final int var2, final int var3, final PointTemp var4) {
    pointCopy(var0, var1[var2] = PointExtended.create());
    final var var5 = PointExtended.create();
    pointAdd(var1[var2], var1[var2], var5, var4);
    for (int var6 = 1; var6 < var3; ++var6) {
      pointAdd(var1[var2 + var6 - 1], var5, var1[var2 + var6] = PointExtended.create(), var4);
    }
  }

  private static void pointDouble(final PointAccum pointAccum) {
    final int[] var1 = pointAccum.x;
    final int[] var2 = pointAccum.y;
    final int[] var3 = pointAccum.z;
    final int[] var4 = pointAccum.u;
    final int[] var7 = pointAccum.v;
    X25519Field.add(pointAccum.x, pointAccum.y, var4);
    X25519Field.sqr(pointAccum.x, var1);
    X25519Field.sqr(pointAccum.y, var2);
    X25519Field.sqr(pointAccum.z, var3);
    X25519Field.add(var3, var3, var3);
    X25519Field.apm(var1, var2, var7, var2);
    X25519Field.sqr(var4, var4);
    X25519Field.sub(var7, var4, var4);
    X25519Field.add(var3, var2, var1);
    X25519Field.carry(var1);
    X25519Field.mul(var1, var2, pointAccum.z);
    X25519Field.mul(var1, var4, pointAccum.x);
    X25519Field.mul(var2, var7, pointAccum.y);
  }

  private static void invertDoubleZs(final PointExtended[] var0) {
    final int var1 = var0.length;
    final int[] var2 = X25519Field.createTable(var1);
    final int[] var3 = X25519Field.create();
    X25519Field.copy(var0[0].z, 0, var3, 0);
    X25519Field.copy(var3, 0, var2, 0);
    for (int var4 = 0; ; ) {
      ++var4;
      if (var4 >= var1) {
        X25519Field.add(var3, var3, var3);
        X25519Field.invVar(var3, var3);
        --var4;
        final int[] var5 = X25519Field.create();
        while (var4 > 0) {
          final int var6 = var4--;
          X25519Field.copy(var2, var4 * 10, var5, 0);
          X25519Field.mul(var5, var3, var5);
          X25519Field.mul(var3, var0[var6].z, var3);
          X25519Field.copy(var5, 0, var0[var6].z, 0);
        }

        X25519Field.copy(var3, 0, var0[0].z, 0);
        return;
      }

      X25519Field.mul(var3, var0[var4].z, var3);
      X25519Field.copy(var3, 0, var2, var4 * 10);
    }
  }

  static {
    gf1[0] = 1;

    final byte var1 = 16;
    final byte var2 = 64;
    final int var3 = var1 * 2 + var2;
    final var var4 = new PointExtended[var3];
    final var var5 = PointTemp.create();
    final var var6 = PointAffine.create();
    X25519Field.copy(B_x, 0, var6.x, 0);
    X25519Field.copy(B_y, 0, var6.y, 0);
    pointPrecompute(var6, var4, 0, var1, var5);
    final var var7 = PointAffine.create();
    X25519Field.copy(B128_x, 0, var7.x, 0);
    X25519Field.copy(B128_y, 0, var7.y, 0);
    pointPrecompute(var7, var4, var1, var1, var5);
    final var var8 = PointAccum.create();
    X25519Field.copy(B_x, 0, var8.x, 0);
    X25519Field.copy(B_y, 0, var8.y, 0);
    X25519Field.one(var8.z);
    X25519Field.copy(var8.x, 0, var8.u, 0);
    X25519Field.copy(var8.y, 0, var8.v, 0);
    int var9 = var1 * 2;

    final var var10 = new PointExtended[4];
    for (int var11 = 0; var11 < 4; ++var11) {
      var10[var11] = PointExtended.create();
    }

    final var var19 = PointExtended.create();

    int var12;
    PointExtended var13;
    int var14;
    for (var12 = 0; var12 < 8; ++var12) {
      var13 = var4[var9++] = PointExtended.create();

      int var15;
      for (var14 = 0; var14 < 4; ++var14) {
        if (var14 == 0) {
          pointCopy(var8, var13);
        } else {
          pointCopy(var8, var19);
          pointAdd(var13, var19, var13, var5);
        }

        pointDouble(var8);
        pointCopy(var8, var10[var14]);
        if (var12 + var14 != 10) {
          for (var15 = 1; var15 < 8; ++var15) {
            pointDouble(var8);
          }
        }
      }

      X25519Field.negate(var13.x, var13.x);
      X25519Field.negate(var13.t, var13.t);

      for (var14 = 0; var14 < 3; ++var14) {
        var15 = 1 << var14;
        for (int var16 = 0; var16 < var15; ++var9) {
          var4[var9] = PointExtended.create();
          pointAdd(var4[var9 - var15], var10[var14], var4[var9], var5);
          ++var16;
        }
      }
    }

    invertDoubleZs(var4);

    PointPrecomp var22;
    for (var12 = 0; var12 < var1; ++var12) {
      var13 = var4[var12];
      var22 = PointPrecomp.create();
      X25519Field.mul(var13.x, var13.z, var13.x);
      X25519Field.mul(var13.y, var13.z, var13.y);
      X25519Field.apm(var13.y, var13.x, var22.ypx_h, var22.ymx_h);
      X25519Field.mul(var13.x, var13.y, var22.xyd);
      X25519Field.mul(var22.xyd, C_d4, var22.xyd);
      X25519Field.normalize(var22.ymx_h);
      X25519Field.normalize(var22.ypx_h);
      X25519Field.normalize(var22.xyd);
    }


    for (var12 = 0; var12 < var1; ++var12) {
      var13 = var4[var1 + var12];
      var22 = PointPrecomp.create();
      X25519Field.mul(var13.x, var13.z, var13.x);
      X25519Field.mul(var13.y, var13.z, var13.y);
      X25519Field.apm(var13.y, var13.x, var22.ypx_h, var22.ymx_h);
      X25519Field.mul(var13.x, var13.y, var22.xyd);
      X25519Field.mul(var22.xyd, C_d4, var22.xyd);
      X25519Field.normalize(var22.ymx_h);
      X25519Field.normalize(var22.ypx_h);
      X25519Field.normalize(var22.xyd);
    }

    PRECOMP_BASE_COMB = X25519Field.createTable(var2 * 3);
    final var var20 = PointPrecomp.create();
    int var21 = 0;
    for (var14 = var1 * 2; var14 < var3; ++var14) {
      PointExtended var23 = var4[var14];
      X25519Field.mul(var23.x, var23.z, var23.x);
      X25519Field.mul(var23.y, var23.z, var23.y);
      X25519Field.apm(var23.y, var23.x, var20.ypx_h, var20.ymx_h);
      X25519Field.mul(var23.x, var23.y, var20.xyd);
      X25519Field.mul(var20.xyd, C_d4, var20.xyd);
      X25519Field.normalize(var20.ymx_h);
      X25519Field.normalize(var20.ypx_h);
      X25519Field.normalize(var20.xyd);
      X25519Field.copy(var20.ymx_h, 0, PRECOMP_BASE_COMB, var21);
      var21 += 10;
      X25519Field.copy(var20.ypx_h, 0, PRECOMP_BASE_COMB, var21);
      var21 += 10;
      X25519Field.copy(var20.xyd, 0, PRECOMP_BASE_COMB, var21);
      var21 += 10;
    }
  }


  public static boolean isNotOnCurve(final byte[] p) {
    final long[][] r = new long[4][16];
    final long[] t = new long[16];
    final long[] chk = new long[16];
    final long[] num = new long[16];
    final long[] den = new long[16];
    final long[] den2 = new long[16];
    final long[] den4 = new long[16];
    final long[] den6 = new long[16];

    set25519(r[2]);
    unpack25519(r[1], p);
    S(num, r[1]);
    M(den, num, D);
    Z(num, num, r[2]);
    A(den, r[2], den);

    S(den2, den);
    S(den4, den2);
    M(den6, den4, den2);
    M(t, den6, num);
    M(t, t, den);

    pow2523(t, t);
    M(t, t, num);
    M(t, t, den);
    M(t, t, den);
    M(r[0], t, den);

    S(chk, r[0]);
    M(chk, chk, den);
    if (neq25519(chk, num) != 0) {
      M(r[0], r[0], I);
    }

    S(chk, r[0]);
    M(chk, chk, den);
    return neq25519(chk, num) != 0;
  }

  private static void set25519(final long[] r) {
    System.arraycopy(Ed25519Util.gf1, 0, r, 0, 16);
  }

  private static void unpack25519(final long[] o, final byte[] n) {
    for (int i = 0; i < 16; i++) {
      o[i] = (n[2 * i] & 0xff) + ((long) ((n[2 * i + 1] << 8) & 0xffff));
    }
    o[15] &= 0x7fff;
  }

  private static void car25519(final long[] o) {
    long v, c = 1;
    for (int i = 0; i < 16; i++) {
      v = o[i] + c + 65535;
      c = v >> 16;
      o[i] = v - c * 65536;
    }
    o[0] += c - 1 + 37 * (c - 1);
  }

  private static void sel25519(final long[] p, final long[] q, final int b) {
    long t, c = -b;
    for (int i = 0; i < 16; i++) {
      t = c & (p[i] ^ q[i]);
      p[i] ^= t;
      q[i] ^= t;
    }
  }

  private static void pack25519(final byte[] o, final long[] n) {
    final long[] m = new long[16], t = new long[16];
    System.arraycopy(n, 0, t, 0, 16);
    car25519(t);
    car25519(t);
    car25519(t);
    for (int j = 0, b; j < 2; j++) {
      m[0] = t[0] - 0xffed;
      for (int i = 1; i < 15; i++) {
        m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
        m[i - 1] &= 0xffff;
      }
      m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
      b = (int) ((m[15] >> 16) & 1);
      m[14] &= 0xffff;
      sel25519(t, m, 1 - b);
    }
    for (int i = 0; i < 16; i++) {
      o[2 * i] = (byte) (t[i] & 0xff);
      o[2 * i + 1] = (byte) (t[i] >> 8);
    }
  }

  private static int crypto_verify_32(final byte[] x, final byte[] y) {
    int d = 0;
    for (int i = 0; i < 32; i++) {
      d |= (x[i] ^ y[i]) & 0xff;
    }
    return (1 & ((d - 1) >>> 8)) - 1;
  }

  private static int neq25519(final long[] a, final long[] b) {
    final byte[] c = new byte[32], d = new byte[32];
    pack25519(c, a);
    pack25519(d, b);
    return crypto_verify_32(c, d);
  }

  private static void pow2523(final long[] o, final long[] i) {
    final long[] c = new long[16];
    System.arraycopy(i, 0, c, 0, 16);
    for (int a = 250; a >= 0; a--) {
      S(c, c);
      if (a != 1) {
        M(c, c, i);
      }
    }
    System.arraycopy(c, 0, o, 0, 16);
  }

  private static void A(final long[] o, final long[] a, final long[] b) {
    for (int i = 0; i < 16; i++) {
      o[i] = a[i] + b[i];
    }
  }

  private static void Z(final long[] o, final long[] a, final long[] b) {
    for (int i = 0; i < 16; i++) {
      o[i] = a[i] - b[i];
    }
  }

  private static void S(final long[] o, final long[] a) {
    M(o, a, a);
  }

  private static void M(final long[] o, final long[] a, final long[] b) {
    long v, c,
        t0 = 0, t1 = 0, t2 = 0, t3 = 0, t4 = 0, t5 = 0, t6 = 0, t7 = 0,
        t8 = 0, t9 = 0, t10 = 0, t11 = 0, t12 = 0, t13 = 0, t14 = 0, t15 = 0,
        t16 = 0, t17 = 0, t18 = 0, t19 = 0, t20 = 0, t21 = 0, t22 = 0, t23 = 0,
        t24 = 0, t25 = 0, t26 = 0, t27 = 0, t28 = 0, t29 = 0, t30 = 0,
        b0 = b[0],
        b1 = b[1],
        b2 = b[2],
        b3 = b[3],
        b4 = b[4],
        b5 = b[5],
        b6 = b[6],
        b7 = b[7],
        b8 = b[8],
        b9 = b[9],
        b10 = b[10],
        b11 = b[11],
        b12 = b[12],
        b13 = b[13],
        b14 = b[14],
        b15 = b[15];

    v = a[0];
    t0 += v * b0;
    t1 += v * b1;
    t2 += v * b2;
    t3 += v * b3;
    t4 += v * b4;
    t5 += v * b5;
    t6 += v * b6;
    t7 += v * b7;
    t8 += v * b8;
    t9 += v * b9;
    t10 += v * b10;
    t11 += v * b11;
    t12 += v * b12;
    t13 += v * b13;
    t14 += v * b14;
    t15 += v * b15;
    v = a[1];
    t1 += v * b0;
    t2 += v * b1;
    t3 += v * b2;
    t4 += v * b3;
    t5 += v * b4;
    t6 += v * b5;
    t7 += v * b6;
    t8 += v * b7;
    t9 += v * b8;
    t10 += v * b9;
    t11 += v * b10;
    t12 += v * b11;
    t13 += v * b12;
    t14 += v * b13;
    t15 += v * b14;
    t16 += v * b15;
    v = a[2];
    t2 += v * b0;
    t3 += v * b1;
    t4 += v * b2;
    t5 += v * b3;
    t6 += v * b4;
    t7 += v * b5;
    t8 += v * b6;
    t9 += v * b7;
    t10 += v * b8;
    t11 += v * b9;
    t12 += v * b10;
    t13 += v * b11;
    t14 += v * b12;
    t15 += v * b13;
    t16 += v * b14;
    t17 += v * b15;
    v = a[3];
    t3 += v * b0;
    t4 += v * b1;
    t5 += v * b2;
    t6 += v * b3;
    t7 += v * b4;
    t8 += v * b5;
    t9 += v * b6;
    t10 += v * b7;
    t11 += v * b8;
    t12 += v * b9;
    t13 += v * b10;
    t14 += v * b11;
    t15 += v * b12;
    t16 += v * b13;
    t17 += v * b14;
    t18 += v * b15;
    v = a[4];
    t4 += v * b0;
    t5 += v * b1;
    t6 += v * b2;
    t7 += v * b3;
    t8 += v * b4;
    t9 += v * b5;
    t10 += v * b6;
    t11 += v * b7;
    t12 += v * b8;
    t13 += v * b9;
    t14 += v * b10;
    t15 += v * b11;
    t16 += v * b12;
    t17 += v * b13;
    t18 += v * b14;
    t19 += v * b15;
    v = a[5];
    t5 += v * b0;
    t6 += v * b1;
    t7 += v * b2;
    t8 += v * b3;
    t9 += v * b4;
    t10 += v * b5;
    t11 += v * b6;
    t12 += v * b7;
    t13 += v * b8;
    t14 += v * b9;
    t15 += v * b10;
    t16 += v * b11;
    t17 += v * b12;
    t18 += v * b13;
    t19 += v * b14;
    t20 += v * b15;
    v = a[6];
    t6 += v * b0;
    t7 += v * b1;
    t8 += v * b2;
    t9 += v * b3;
    t10 += v * b4;
    t11 += v * b5;
    t12 += v * b6;
    t13 += v * b7;
    t14 += v * b8;
    t15 += v * b9;
    t16 += v * b10;
    t17 += v * b11;
    t18 += v * b12;
    t19 += v * b13;
    t20 += v * b14;
    t21 += v * b15;
    v = a[7];
    t7 += v * b0;
    t8 += v * b1;
    t9 += v * b2;
    t10 += v * b3;
    t11 += v * b4;
    t12 += v * b5;
    t13 += v * b6;
    t14 += v * b7;
    t15 += v * b8;
    t16 += v * b9;
    t17 += v * b10;
    t18 += v * b11;
    t19 += v * b12;
    t20 += v * b13;
    t21 += v * b14;
    t22 += v * b15;
    v = a[8];
    t8 += v * b0;
    t9 += v * b1;
    t10 += v * b2;
    t11 += v * b3;
    t12 += v * b4;
    t13 += v * b5;
    t14 += v * b6;
    t15 += v * b7;
    t16 += v * b8;
    t17 += v * b9;
    t18 += v * b10;
    t19 += v * b11;
    t20 += v * b12;
    t21 += v * b13;
    t22 += v * b14;
    t23 += v * b15;
    v = a[9];
    t9 += v * b0;
    t10 += v * b1;
    t11 += v * b2;
    t12 += v * b3;
    t13 += v * b4;
    t14 += v * b5;
    t15 += v * b6;
    t16 += v * b7;
    t17 += v * b8;
    t18 += v * b9;
    t19 += v * b10;
    t20 += v * b11;
    t21 += v * b12;
    t22 += v * b13;
    t23 += v * b14;
    t24 += v * b15;
    v = a[10];
    t10 += v * b0;
    t11 += v * b1;
    t12 += v * b2;
    t13 += v * b3;
    t14 += v * b4;
    t15 += v * b5;
    t16 += v * b6;
    t17 += v * b7;
    t18 += v * b8;
    t19 += v * b9;
    t20 += v * b10;
    t21 += v * b11;
    t22 += v * b12;
    t23 += v * b13;
    t24 += v * b14;
    t25 += v * b15;
    v = a[11];
    t11 += v * b0;
    t12 += v * b1;
    t13 += v * b2;
    t14 += v * b3;
    t15 += v * b4;
    t16 += v * b5;
    t17 += v * b6;
    t18 += v * b7;
    t19 += v * b8;
    t20 += v * b9;
    t21 += v * b10;
    t22 += v * b11;
    t23 += v * b12;
    t24 += v * b13;
    t25 += v * b14;
    t26 += v * b15;
    v = a[12];
    t12 += v * b0;
    t13 += v * b1;
    t14 += v * b2;
    t15 += v * b3;
    t16 += v * b4;
    t17 += v * b5;
    t18 += v * b6;
    t19 += v * b7;
    t20 += v * b8;
    t21 += v * b9;
    t22 += v * b10;
    t23 += v * b11;
    t24 += v * b12;
    t25 += v * b13;
    t26 += v * b14;
    t27 += v * b15;
    v = a[13];
    t13 += v * b0;
    t14 += v * b1;
    t15 += v * b2;
    t16 += v * b3;
    t17 += v * b4;
    t18 += v * b5;
    t19 += v * b6;
    t20 += v * b7;
    t21 += v * b8;
    t22 += v * b9;
    t23 += v * b10;
    t24 += v * b11;
    t25 += v * b12;
    t26 += v * b13;
    t27 += v * b14;
    t28 += v * b15;
    v = a[14];
    t14 += v * b0;
    t15 += v * b1;
    t16 += v * b2;
    t17 += v * b3;
    t18 += v * b4;
    t19 += v * b5;
    t20 += v * b6;
    t21 += v * b7;
    t22 += v * b8;
    t23 += v * b9;
    t24 += v * b10;
    t25 += v * b11;
    t26 += v * b12;
    t27 += v * b13;
    t28 += v * b14;
    t29 += v * b15;
    v = a[15];
    t15 += v * b0;
    t16 += v * b1;
    t17 += v * b2;
    t18 += v * b3;
    t19 += v * b4;
    t20 += v * b5;
    t21 += v * b6;
    t22 += v * b7;
    t23 += v * b8;
    t24 += v * b9;
    t25 += v * b10;
    t26 += v * b11;
    t27 += v * b12;
    t28 += v * b13;
    t29 += v * b14;
    t30 += v * b15;

    t0 += 38 * t16;
    t1 += 38 * t17;
    t2 += 38 * t18;
    t3 += 38 * t19;
    t4 += 38 * t20;
    t5 += 38 * t21;
    t6 += 38 * t22;
    t7 += 38 * t23;
    t8 += 38 * t24;
    t9 += 38 * t25;
    t10 += 38 * t26;
    t11 += 38 * t27;
    t12 += 38 * t28;
    t13 += 38 * t29;
    t14 += 38 * t30;
    // t15 left as is

    // first car
    c = 1;
    v = t0 + c + 65535;
    c = v >> 16;
    t0 = v - c * 65536;
    v = t1 + c + 65535;
    c = v >> 16;
    t1 = v - c * 65536;
    v = t2 + c + 65535;
    c = v >> 16;
    t2 = v - c * 65536;
    v = t3 + c + 65535;
    c = v >> 16;
    t3 = v - c * 65536;
    v = t4 + c + 65535;
    c = v >> 16;
    t4 = v - c * 65536;
    v = t5 + c + 65535;
    c = v >> 16;
    t5 = v - c * 65536;
    v = t6 + c + 65535;
    c = v >> 16;
    t6 = v - c * 65536;
    v = t7 + c + 65535;
    c = v >> 16;
    t7 = v - c * 65536;
    v = t8 + c + 65535;
    c = v >> 16;
    t8 = v - c * 65536;
    v = t9 + c + 65535;
    c = v >> 16;
    t9 = v - c * 65536;
    v = t10 + c + 65535;
    c = v >> 16;
    t10 = v - c * 65536;
    v = t11 + c + 65535;
    c = v >> 16;
    t11 = v - c * 65536;
    v = t12 + c + 65535;
    c = v >> 16;
    t12 = v - c * 65536;
    v = t13 + c + 65535;
    c = v >> 16;
    t13 = v - c * 65536;
    v = t14 + c + 65535;
    c = v >> 16;
    t14 = v - c * 65536;
    v = t15 + c + 65535;
    c = v >> 16;
    t15 = v - c * 65536;
    t0 += c - 1 + 37 * (c - 1);

    // second car
    c = 1;
    v = t0 + c + 65535;
    c = v >> 16;
    t0 = v - c * 65536;
    v = t1 + c + 65535;
    c = v >> 16;
    t1 = v - c * 65536;
    v = t2 + c + 65535;
    c = v >> 16;
    t2 = v - c * 65536;
    v = t3 + c + 65535;
    c = v >> 16;
    t3 = v - c * 65536;
    v = t4 + c + 65535;
    c = v >> 16;
    t4 = v - c * 65536;
    v = t5 + c + 65535;
    c = v >> 16;
    t5 = v - c * 65536;
    v = t6 + c + 65535;
    c = v >> 16;
    t6 = v - c * 65536;
    v = t7 + c + 65535;
    c = v >> 16;
    t7 = v - c * 65536;
    v = t8 + c + 65535;
    c = v >> 16;
    t8 = v - c * 65536;
    v = t9 + c + 65535;
    c = v >> 16;
    t9 = v - c * 65536;
    v = t10 + c + 65535;
    c = v >> 16;
    t10 = v - c * 65536;
    v = t11 + c + 65535;
    c = v >> 16;
    t11 = v - c * 65536;
    v = t12 + c + 65535;
    c = v >> 16;
    t12 = v - c * 65536;
    v = t13 + c + 65535;
    c = v >> 16;
    t13 = v - c * 65536;
    v = t14 + c + 65535;
    c = v >> 16;
    t14 = v - c * 65536;
    v = t15 + c + 65535;
    c = v >> 16;
    t15 = v - c * 65536;
    t0 += c - 1 + 37 * (c - 1);

    o[0] = t0;
    o[1] = t1;
    o[2] = t2;
    o[3] = t3;
    o[4] = t4;
    o[5] = t5;
    o[6] = t6;
    o[7] = t7;
    o[8] = t8;
    o[9] = t9;
    o[10] = t10;
    o[11] = t11;
    o[12] = t12;
    o[13] = t13;
    o[14] = t14;
    o[15] = t15;
  }

  private static void groupCombBits(final int[] var0) {
    for (int var1 = 0; var1 < var0.length; ++var1) {
      var0[var1] = Interleave.shuffle2(var0[var1]);
    }
  }

  private static void pointLookup(final int var0, final int var1, final PointPrecomp pointPrecomp) {
    int var3 = var0 * 8 * 3 * 10;
    for (int var4 = 0, var5; var4 < 8; ++var4) {
      var5 = (var4 ^ var1) - 1 >> 31;
      X25519Field.cmov(var5, PRECOMP_BASE_COMB, var3, pointPrecomp.ymx_h, 0);
      var3 += 10;
      X25519Field.cmov(var5, PRECOMP_BASE_COMB, var3, pointPrecomp.ypx_h, 0);
      var3 += 10;
      X25519Field.cmov(var5, PRECOMP_BASE_COMB, var3, pointPrecomp.xyd, 0);
      var3 += 10;
    }
  }

  private static void pointAdd(final PointPrecomp var0, final PointAccum pointAccum, final PointTemp pointTemp) {
    final int[] var3 = pointAccum.x;
    final int[] var4 = pointAccum.y;
    final int[] var5 = pointTemp.r0;
    final int[] var6 = pointAccum.u;
    final int[] var9 = pointAccum.v;
    X25519Field.apm(pointAccum.y, pointAccum.x, var4, var3);
    X25519Field.mul(var3, var0.ymx_h, var3);
    X25519Field.mul(var4, var0.ypx_h, var4);
    X25519Field.mul(pointAccum.u, pointAccum.v, var5);
    X25519Field.mul(var5, var0.xyd, var5);
    X25519Field.apm(var4, var3, var9, var6);
    X25519Field.apm(pointAccum.z, var5, var4, var3);
    X25519Field.mul(var3, var4, pointAccum.z);
    X25519Field.mul(var3, var6, pointAccum.x);
    X25519Field.mul(var4, var9, pointAccum.y);
  }

  private static PointAccum scalarMultBase(final byte[] var0) {
    final int[] var2 = new int[8];
    Scalar25519.decode(var0, var2);
    Scalar25519.toSignedDigits(var2);
    groupCombBits(var2);
    final var pointPrecomp = PointPrecomp.create();
    final var pointTemp = PointTemp.create();
    final var pointAccum = PointAccum.pointSetNeutral();

    for (int var5 = 0, var6 = 28; ; ) {
      for (int var7 = 0; var7 < 8; ++var7) {
        final int var8 = var2[var7] >>> var6;
        final int var9 = var8 >>> 3 & 1;
        final int var10 = (var8 ^ -var9) & 7;
        pointLookup(var7, var10, pointPrecomp);
        X25519Field.cnegate(var5 ^ var9, pointAccum.x);
        X25519Field.cnegate(var5 ^ var9, pointAccum.u);
        var5 = var9;
        pointAdd(pointPrecomp, pointAccum, pointTemp);
      }

      var6 -= 4;
      if (var6 < 0) {
        X25519Field.cnegate(var5, pointAccum.x);
        X25519Field.cnegate(var5, pointAccum.u);
        return pointAccum;
      }

      pointDouble(pointAccum);
    }
  }

  private static void pruneScalar(final byte[] mutableKeyPair, final int privateKeyOffset, final byte[] mutablePublicKey) {
    System.arraycopy(mutableKeyPair, privateKeyOffset, mutablePublicKey, 0, 32);
    mutablePublicKey[0] = (byte) (mutablePublicKey[0] & 248);
    mutablePublicKey[31] = (byte) (mutablePublicKey[31] & 127);
    mutablePublicKey[31] = (byte) (mutablePublicKey[31] | 64);
  }

  private static void normalizeToAffine(final PointAccum pointAccum, final PointAffine pointAffine) {
    X25519Field.inv(pointAccum.z, pointAffine.y);
    X25519Field.mul(pointAffine.y, pointAccum.x, pointAffine.x);
    X25519Field.mul(pointAffine.y, pointAccum.y, pointAffine.y);
    X25519Field.normalize(pointAffine.x);
    X25519Field.normalize(pointAffine.y);
  }

  private static void encodePoint(final PointAffine var0, final byte[] publicKeyOut, final int var2) {
    X25519Field.encode(var0.y, publicKeyOut, var2);
    publicKeyOut[var2 + 32 - 1] = (byte) (publicKeyOut[var2 + 32 - 1] | (var0.x[0] & 1) << 7);
  }

  private static void encodeResult(final PointAccum pointAccum, final byte[] publicKeyOut, final int var2) {
    final var pointAffine = PointAffine.create();
    normalizeToAffine(pointAccum, pointAffine);
    encodePoint(pointAffine, publicKeyOut, var2);
  }

  private static void scalarMultBaseEncoded(final byte[] mutablePublicKey, final byte[] publicKeyOut, final int publicKeyOffset) {
    final var pointAccum = scalarMultBase(mutablePublicKey);
    encodeResult(pointAccum, publicKeyOut, publicKeyOffset);
  }

  public static void generatePublicKey(final byte[] privateKey, final byte[] publicKeyOut) {
    generatePublicKey(privateKey, 0, publicKeyOut, 0);
  }

  public static void generatePublicKey(final byte[] privateKey,
                                       final int privateKeyOffset,
                                       final byte[] publicKeyOut,
                                       final int publicKeyOutOffset) {
    final var digest = Hash.sha512Digest();
    final byte[] mutablePublicKey = new byte[32];
    final byte[] mutableKeyPair = new byte[64];
    generatePublicKey(
        digest,
        privateKey, privateKeyOffset,
        publicKeyOut, publicKeyOutOffset,
        mutablePublicKey, mutableKeyPair
    );
  }

  public static void generatePublicKey(final MessageDigest messageDigest,
                                       final byte[] privateKey,
                                       final int privateKeyOffset,
                                       final byte[] publicKeyOut,
                                       final int publicKeyOutOffset,
                                       final byte[] mutablePublicKey,
                                       final byte[] mutableKeyPair) {
    messageDigest.update(privateKey, privateKeyOffset, 32);
    try {
      messageDigest.digest(mutableKeyPair, 0, 64);
    } catch (final DigestException e) {
      throw new RuntimeException(e);
    }
    pruneScalar(mutableKeyPair, 0, mutablePublicKey);
    scalarMultBaseEncoded(mutablePublicKey, publicKeyOut, publicKeyOutOffset);
  }

  private Ed25519Util() {
  }
}
