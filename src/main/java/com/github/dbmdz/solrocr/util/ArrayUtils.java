package com.github.dbmdz.solrocr.util;

public class ArrayUtils {
  /** Find the index of the largest element in {@param arr} that is smaller than or equal to
   *  {@param x} or -1 if none was found. */
  public static int binaryFloorIdxSearch(int[] arr, int x) {
    if (arr == null || arr.length == 0) {
      return -1;
    }

    int left = 0;
    int right = arr.length - 1;
    int middle;
    int floorIdx = -1;

    while (left <= right) {
      middle = left + (right - left) / 2;
      // Check if current middle is smaller and closer to x
      if (arr[middle] <= x) {
        floorIdx = middle;
        // If we found an exact match, we can stop
        if (arr[middle] == x) {
          break;
        }
        left = middle + 1; // Try to find an even closer value
      } else {
        right = middle - 1;
      }
    }
    return floorIdx;
  }
}
