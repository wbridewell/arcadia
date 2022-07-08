package arcadia;

import java.awt.image.DataBufferByte;
import java.awt.image.BufferedImage;
import java.util.*;
import org.opencv.core.CvType;
import org.opencv.core.Size;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Point;
import org.opencv.core.Core;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfFloat;
import org.opencv.imgproc.Imgproc;

public class ColorHistogram {

  private static Mat readMatrix (BufferedImage bi, int cvtype) {
    Mat readMat = new Mat (bi.getHeight(), bi.getWidth(), cvtype);
    readMat.put(0, 0, ((DataBufferByte) bi.getRaster().getDataBuffer()).getData());
    return readMat;
  }

  private static Mat bufferedImageToMat (BufferedImage bi) {
    return bufferedImageToMat(bi, CvType.CV_32FC3);

  }

  private static Mat bufferedImageToMat (BufferedImage bi, int cvtype) {
    Mat readMat = null;
    Mat newMat = new Mat();

    switch (bi.getType()) {
      case BufferedImage.TYPE_BYTE_GRAY: readMat = readMatrix(bi, CvType.CV_8UC1);
        break;
      case BufferedImage.TYPE_INT_BGR: readMat = readMatrix(bi, CvType.CV_8UC3);
        break;
      case BufferedImage.TYPE_3BYTE_BGR: readMat = readMatrix(bi, CvType.CV_8UC3);
        break;
    }
    if (readMat != null) {
      readMat.convertTo(newMat, cvtype);
      return newMat;
    }
    else {
      return null;
    }
  }

  // taken from (and fixed)
  // http://stackoverflow.com/questions/22464503/how-to-use-opencv-to-calculate-hsv-histogram-in-java-platform

  public static Map<String, Mat> calculateHistogram (BufferedImage image) {
    final int HISTSIZE = 32;
    Mat scratch = bufferedImageToMat(image);
    MatOfInt histSize = new MatOfInt(HISTSIZE);
    MatOfFloat ranges = new MatOfFloat(0.0f, 255.0f);
    List<Mat> channels = new ArrayList<Mat>();
    Mat mask = new Mat();
    Mat hist_h = new Mat();
    Mat hist_s = new Mat();
    Mat hist_v = new Mat();

    Imgproc.cvtColor(scratch, scratch, Imgproc.COLOR_BGR2HSV);
    Core.split(scratch, channels);

    Imgproc.calcHist(Arrays.asList(new Mat[] {channels.get(0)}), new MatOfInt(0), mask, hist_h, histSize, ranges);
    Imgproc.calcHist(Arrays.asList(new Mat[] {channels.get(1)}), new MatOfInt(0), mask, hist_s, histSize, ranges);
    Imgproc.calcHist(Arrays.asList(new Mat[] {channels.get(2)}), new MatOfInt(0), mask, hist_v, histSize, ranges);

    int histWidth = 512;
    int histHeight = 600;
    long binWidth = Math.round((double) histWidth / HISTSIZE);
    Mat histImage = new Mat(histHeight, histWidth, CvType.CV_8UC3, new Scalar(0,0,0));

    Core.normalize(hist_h, hist_h, 0, histImage.rows(), Core.NORM_MINMAX, -1, new Mat() );
    Core.normalize(hist_s, hist_s, 0, histImage.rows(), Core.NORM_MINMAX, -1, new Mat() );
    Core.normalize(hist_v, hist_v, 0, histImage.rows(), Core.NORM_MINMAX, -1, new Mat() );

    for(int i = 0; i < HISTSIZE; i++) {
        Point p1 = new Point(binWidth * (i - 1), histHeight - Math.round(hist_h.get(i - 1, 0)[0]));
        Point p2 = new Point(binWidth * (i), histHeight - Math.round(hist_h.get(i, 0)[0]));
        Core.line(histImage, p1, p2, new Scalar(255, 0, 0), 2, 8, 0);

        Point p3 = new Point(binWidth * (i - 1), histHeight - Math.round(hist_s.get(i - 1, 0)[0]));
        Point p4 = new Point(binWidth * (i), histHeight - Math.round(hist_s.get(i, 0)[0]));
        Core.line(histImage, p3, p4, new Scalar(0, 255, 0), 2, 8, 0);

        Point p5 = new Point(binWidth * (i - 1), histHeight - Math.round(hist_v.get(i - 1, 0)[0]));
        Point p6 = new Point(binWidth * (i), histHeight - Math.round(hist_v.get(i, 0)[0]));
        Core.line(histImage, p5, p6, new Scalar(0, 0, 255), 2, 8, 0);
    }

    Map<String, Mat> hsv = new HashMap<String, Mat>();
    hsv.put("h", hist_h);
    hsv.put("s", hist_s);
    hsv.put("v", hist_v);
    hsv.put("plot", histImage);
    return hsv;
  }
}

