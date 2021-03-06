import org.datavec.api.split.FileSplit;
import org.datavec.image.loader.BaseImageLoader;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.FileStatsStorage;
import org.deeplearning4j.util.ModelSerializer;
import org.deeplearning4j.zoo.model.UNet;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.learning.config.Adam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class TrainUnetModel {
    private static final Logger log = LoggerFactory.getLogger(TrainUnetModel.class);
    private static final int WIDTH = 256;
    private static final int HEIGHT = 256;
    private static final int CHANNELS = 3;
    private static final String MODEL_PATH = System.getProperty("user.home") + "/unet/unet_membrane.hdf5";
    private static final String IMAGES_PATH = System.getProperty("user.home") + "/unet/data/membrane/train/aug";

    /**
     * Performs inline replacement RGB -> BGR
     * for memory performance
     *
     * @param bufferedImage
     * @return
     */
    public static BufferedImage getBGRBufferedImage(BufferedImage bufferedImage) {
        for (int w = 0; w < bufferedImage.getWidth(); w++) {
            for (int h = 0; h < bufferedImage.getHeight(); h++) {
                int p = bufferedImage.getRGB(w, h);
                int a = (p >> 24) & 0xff;
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                // swap r (red) and b (blue) channels
                p = (a << 24) | (b << 16) | (g << 8) | r;
                bufferedImage.setRGB(w, h, p);
            }
        }
        return bufferedImage;
    }

    public static BufferedImage getBGRBufferedImage(File imageFile) {
        try {
            BufferedImage bufferedImage = ImageIO.read(imageFile);
            return getBGRBufferedImage(bufferedImage);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void main(String[] args) {
        try {
            int outputNum = 2; // 0 = cell; 1 = limit
            int batchSize = 10;
            // try to load the .h5 model saved from unet
            String pathToImage;
            if (args.length > 0) {
                // we prodived a filename containing model
                pathToImage = args[0];
            } else {
                pathToImage = System.getProperty("user.home") + "/unet/data/membrane/test/0.png";
            }

            log.info("Using model " + MODEL_PATH);

            DataNormalization scaler = new ImagePreProcessingScaler(); // scale image between 0 and 1
            UnetPathLabelGenerator labeler = new UnetPathLabelGenerator();

            File rootDir = new File(System.getProperty("user.home") + "/unet/data/membrane/train/image");
            String[] allowedExtensions = BaseImageLoader.ALLOWED_FORMATS;
            Random rng = new Random();
            FileSplit inputSplit = new FileSplit(rootDir,allowedExtensions,rng);
            ImageRecordReader imageRecordReader = new ImageRecordReader(HEIGHT,WIDTH,CHANNELS,labeler);
            imageRecordReader.initialize(inputSplit);
            int labelIndex = 1;
            DataSetIterator imageDataSetIterator = new RecordReaderDataSetIterator(imageRecordReader,batchSize,labelIndex,labelIndex,true);
            scaler.fit(imageDataSetIterator);
            imageDataSetIterator.setPreProcessor(scaler);

            UIServer uiServer = UIServer.getInstance();

            StatsStorage statsStorage = new FileStatsStorage(new File(System.getProperty("user.home")+File.separator+"stats.log"));

            uiServer.attach(statsStorage);

            Map<Integer, Double> learningScheduleMap = new HashMap<>();
            learningScheduleMap.put(0, 0.00005);
            learningScheduleMap.put(200, 0.00001);
            learningScheduleMap.put(600, 0.000005);
            learningScheduleMap.put(800, 0.0000001);
//            learningScheduleMap.put(1000, 0.00001);

            int seed = 1234;
            int numberOfChannels = 1;
            int numEpochs = 600;

            ComputationGraph model  = UNet.builder().updater(new Adam(1e-4)).build().init();
//            ComputationGraph model  = UNet.builder().updater(new Adam(new MapSchedule(ScheduleType.ITERATION, learningScheduleMap))).build().init();
            model.addListeners(new ScoreIterationListener(),new StatsListener(statsStorage));
            model.fit(imageDataSetIterator,numEpochs);

            log.warn(model.summary());

            NativeImageLoader loader = new NativeImageLoader(HEIGHT, WIDTH, CHANNELS);
            BufferedImage bufferedBGR = getBGRBufferedImage(new File(pathToImage));
            INDArray imageNative = loader.asMatrix(bufferedBGR);

            log.warn(imageNative.shapeInfoToString());

            imageNative = imageNative.reshape(1, CHANNELS, HEIGHT, WIDTH);
            imageNative = imageNative.divi(255f);

            INDArray[] output = model.output(imageNative);
            for (INDArray out : output) {
                out = out.reshape(1, HEIGHT,WIDTH);
                //out = out.permute(2,1,0);
                BufferedImage bufferedImage = new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_BYTE_GRAY);
                for (int i=0; i < WIDTH; i++) {
                    for (int j=0; j < HEIGHT; j++) {
                        float f = out.getFloat(new int[]{0,j,i});
                        int gray = (int)(f*255.0);
//                        if (gray > 127) {
//                            bufferedImage.setRGB(i,j,Color.WHITE.getRGB());
//                        } else {
//                            bufferedImage.setRGB(i,j,Color.BLACK.getRGB());
//                        }
                      bufferedImage.setRGB(i,j,new Color(gray,gray,gray).getRGB());
                    }
                }
                ImageIO.write(bufferedImage,"png",new File("outputUnet.png"));
//                float[] values = out.toFloatVector();
//                System.out.println(Arrays.toString(values));
            }
        } catch (Exception e) {
            System.err.println("Oooooops");
            e.printStackTrace();
        }
    }
}

