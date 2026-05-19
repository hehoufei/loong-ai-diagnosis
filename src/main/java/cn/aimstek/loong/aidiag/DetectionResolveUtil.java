package cn.aimstek.loong.aidiag;

public class DetectionResolveUtil {


    public static int[] resolve(Short data) {
        int[] arr1 = new int[]{11, 12, 13, 14, 15, 16, 17, 18, 19};
        int[] arr2 = new int[]{21, 22, 23, 24, 25, 26, 27, 28, 29};
        int[] ints = new int[2];
        int[][] arr = new int[][]{arr2, arr1};
        byte[] bytes = ByteUtils.shortToByteArray(data);
        for (int i = 0; i < arr.length; i++) {
            resolve(ByteUtils.byteToBooleanArray(bytes[i]), arr[i], ints, 1 - i);
        }
        return ints;
    }

    private static void resolve(boolean[] booleans, int[] arr, int[] ints, int index) {
        for (int i = 0; i < arr.length - 1; i++) {
            if (booleans[i]) {
                ints[index] = arr[i + 1];
            }
        }
        if (ints[index] == 0) {
            ints[index] = arr[0];
        }
    }

    public static void main(String[] args) {
        String s = "100000001";
        short i = (short) Integer.parseInt(s, 2);
        int[] resolve = resolve(i);
        System.out.println(resolve[0]);
        System.out.println(resolve[1]);
    }
}
