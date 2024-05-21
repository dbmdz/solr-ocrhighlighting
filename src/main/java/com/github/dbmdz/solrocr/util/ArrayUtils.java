package com.github.dbmdz.solrocr.util;

public class ArrayUtils {
  public static int binaryFloorIdxSearch(int[] arr, int x) {
    if (arr == null || arr.length == 0) {
      return -1;
    }

    int left = 0;
    int right = arr.length - 1;
    int middle = -1;
    int floorIdx = -1;

    while (left <= right) {
      middle = left + (right - left) / 2;
      // Check if current middle is smaller and closer to x
      if (arr[middle] <= x) {
        floorIdx = middle;
        left = middle + 1; // Try to find an even closer value
      } else {
        right = middle - 1;
      }
    }
    return floorIdx;
  }
}
