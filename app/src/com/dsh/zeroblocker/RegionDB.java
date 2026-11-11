package com.dsh.zeroblocker;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 号码归属地查询（手机号段 + 固话区号 -> 省 / 市）。
 * 数据来自 phone2region（MIT License，https://github.com/iBugCode/phone2region），
 * 已压缩成 assets/regions.bin（连续号段合并 + varint 编码）。
 */
public final class RegionDB {

    private static RegionDB instance;

    private int[] starts;
    private int[] lens;
    private int[] cityIds;

    private RegionDB() {
    }

    public static synchronized RegionDB get(Context c) {
        if (instance == null) {
            RegionDB db = new RegionDB();
            try {
                db.load(c);
            } catch (Throwable ignored) {
            }
            instance = db;
        }
        return instance;
    }

    private void load(Context c) throws Exception {
        InputStream in = c.getAssets().open("regions.bin");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        in.close();
        byte[] all = bos.toByteArray();
        if (all.length < 8 || all[0] != 'R' || all[1] != 'G' || all[2] != '0' || all[3] != '1') {
            return;
        }
        int[] pos = new int[]{4};
        int n = readVarint(all, pos);
        starts = new int[n];
        lens = new int[n];
        cityIds = new int[n];
        int prevEnd = 0;
        for (int i = 0; i < n; i++) {
            int delta = readVarint(all, pos);
            int len = readVarint(all, pos);
            int city = readVarint(all, pos);
            starts[i] = prevEnd + delta;
            lens[i] = len;
            cityIds[i] = city;
            prevEnd = starts[i] + len;
        }
    }

    private static int readVarint(byte[] b, int[] pos) {
        int result = 0;
        int shift = 0;
        while (true) {
            int x = b[pos[0]++] & 0xff;
            result |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }

    /** 返回城市索引（RegionData.CITIES），未知返回 -1。 */
    public int cityOfNumber(String digits) {
        if (starts == null || digits == null) {
            return -1;
        }
        String d = normalize(digits);
        if (d.startsWith("0")) {
            return cityOfLandline(d);
        }
        if (d.length() == 11 && d.charAt(0) == '1') {
            try {
                int prefix = Integer.parseInt(d.substring(0, 7));
                int idx = findRun(prefix);
                if (idx >= 0) {
                    return cityIds[idx];
                }
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 返回省份索引（RegionData.PROVINCES），未知返回 -1。 */
    public int provinceOfNumber(String digits) {
        int city = cityOfNumber(digits);
        return city >= 0 ? RegionData.CITY_PROVINCE[city] : -1;
    }

    /** 城市名字。 */
    public static String cityName(int cityId) {
        return (cityId >= 0 && cityId < RegionData.CITIES.length) ? RegionData.CITIES[cityId] : "";
    }

    /** 省份名字。 */
    public static String provinceName(int provinceId) {
        return (provinceId >= 0 && provinceId < RegionData.PROVINCES.length) ? RegionData.PROVINCES[provinceId] : "";
    }

    private String normalize(String digits) {
        String d = digits;
        if (d.startsWith("0086") && d.length() > 4) {
            d = d.substring(4);
        } else if (d.startsWith("86") && d.length() >= 12) {
            d = d.substring(2);
        }
        return d;
    }

    private int cityOfLandline(String d) {
        for (int len = 4; len >= 3; len--) {
            if (d.length() >= len + 4) {
                String ac = d.substring(0, len);
                int idx = Arrays.binarySearch(RegionData.AREA_CODES, ac);
                if (idx >= 0) {
                    return RegionData.AREA_CITY[idx];
                }
            }
        }
        return -1;
    }

    private int findRun(int prefix) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int s = starts[mid];
            if (prefix < s) {
                hi = mid - 1;
            } else if (prefix >= s + lens[mid]) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }
}
