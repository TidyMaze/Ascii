import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * Created by Yann on 12/09/2015 for this wonderful project.
 */
public class AsciiApp {

    public static final boolean DEBUG = true;
    public static final String FONT_NAME = "Source Code Pro";
    public static final String DEFAULT_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789&é\"'(-è_çà)=~#{[|`\\^@]}€^¨$£¤*µù%!§:/;.,?<> ";
    private static JLabel labelImage;
    private static JFrame window;
    private static String allowedCharacters;
    private static Font font;
    private static Optional<File> imageFile;
    private static SwingWorker<BufferedImage, Object> updateThread;

    public static void showImage(BufferedImage image) {
        ImageIcon imageIcon = new ImageIcon(image);
        labelImage.setIcon(imageIcon);
    }

    public static void main(String[] args) throws IOException, InterruptedException, ClassNotFoundException, UnsupportedLookAndFeelException, InstantiationException, IllegalAccessException {
        allowedCharacters = DEFAULT_CHARACTERS;
        font = new Font(FONT_NAME, Font.PLAIN, 8);
        imageFile = Optional.empty();

        createWindow();
        updateImage();
    }

    private static BufferedImage generateImage(String allowedCharacters, File image, Font font) throws IOException {
        FontMetrics fontMetrics = getFontMetrics(font);
        int[] widths = fontMetrics.getWidths();
        final int BLOC_WIDTH = widths[1];
        final int BLOC_HEIGHT = fontMetrics.getHeight();

        BufferedImage originalImage = ImageIO.read(image);
        int nbBlocsLine = (int)Math.ceil(originalImage.getWidth()/(double)BLOC_WIDTH);
        int nbBlocsColumn = (int)Math.ceil(originalImage.getHeight() /(double)BLOC_HEIGHT);

        BufferedImage inGrey = convertGreyScale(originalImage);
        int minBrightness = findMinBrightness(inGrey);
        int maxBrightness = findMaxBrightness(inGrey);

        int[] characterBrightness = getCharactersBrightness(allowedCharacters, font, fontMetrics, BLOC_WIDTH, BLOC_HEIGHT);
        int minCharBrightness = getMinCharBrightness(characterBrightness);
        int maxCharBrightness = getMaxCharBrightness(characterBrightness);

        BufferedImage inGreyLevelled = levelImage(inGrey, minBrightness, maxBrightness, minCharBrightness, maxCharBrightness);
        int[][] brightness = generateGreyBlocs(BLOC_WIDTH, BLOC_HEIGHT, nbBlocsLine, nbBlocsColumn, inGreyLevelled);
        BufferedImage asciiImage = generateASCII(allowedCharacters, font, BLOC_WIDTH, BLOC_HEIGHT, nbBlocsLine, nbBlocsColumn, characterBrightness, brightness);

        return asciiImage;
    }

    private static FontMetrics getFontMetrics(Font font) {
        Canvas c = new Canvas();
        return c.getFontMetrics(font);
    }

    private static int[] getCharactersBrightness(String allowedCharacters, Font font, FontMetrics fontMetrics, int BLOC_WIDTH, int BLOC_HEIGHT) {
        int allCharsWidth = fontMetrics.stringWidth(allowedCharacters);
        int allCharsHeight = fontMetrics.getHeight();

        if(allCharsWidth>0 && allCharsHeight>0) {
            BufferedImage charsImage = new BufferedImage(allCharsWidth, allCharsHeight, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = charsImage.getGraphics();
            graphics.setFont(font);
            graphics.drawString(allowedCharacters, 0, allCharsHeight - fontMetrics.getDescent());
            return getCharactersBrightnessFromCharImage(allowedCharacters, BLOC_WIDTH, BLOC_HEIGHT, charsImage);
        } else {
            return new int[allowedCharacters.length()];
        }
    }

    private static int[] getCharactersBrightnessFromCharImage(String allowedCharacters, int BLOC_WIDTH, int BLOC_HEIGHT, BufferedImage charsImage) {
        int[] characterBrightness = new int[allowedCharacters.length()];
        for(int numLettre = 0; numLettre<allowedCharacters.length();numLettre++){
            int sum = 0;
            for(int y=0;y<BLOC_HEIGHT;y++){
                for(int x=0;x<BLOC_WIDTH;x++){
                    int xToPick = numLettre * BLOC_WIDTH + x;
                    int yToPick = y;
                    Color pixelColor = new Color(charsImage.getRGB(xToPick, yToPick));
                    double colorBrightness = getColorBrightness(pixelColor);
                    sum += colorBrightness;
                }
            }
            characterBrightness[numLettre] = (int)Math.round(sum/(double)(BLOC_WIDTH*BLOC_HEIGHT));
        }

        return characterBrightness;
    }

    private static int getMinCharBrightness(int[] characterBrightness) {
        return Arrays.stream(characterBrightness).min().orElse(255);
    }

    private static int getMaxCharBrightness(int[] characterBrightness) {
        return Arrays.stream(characterBrightness).max().orElse(0);
    }

    private static BufferedImage generateASCII(String allowedCharacters, Font font, int BLOC_WIDTH, int BLOC_HEIGHT, int nbBlocsLine, int nbBlocsColumn, int[] characterBrightness, int[][] brightness) {
        BufferedImage asciiImage = new BufferedImage(BLOC_WIDTH*nbBlocsLine, BLOC_HEIGHT*nbBlocsColumn, BufferedImage.TYPE_INT_RGB);
        Graphics asciiGraphics = asciiImage.getGraphics();
        asciiGraphics.setFont(font);
        for(int line=0;line<nbBlocsColumn;line++) {
            for (int column = 0; column < nbBlocsLine; column++) {
                int targetBrightness = brightness[line][column];

                if(allowedCharacters.length()>0) {
                    char bestChar = findBestCharForBrightness(allowedCharacters, characterBrightness, targetBrightness);
                    String character = ""+ bestChar;
                    asciiGraphics.drawString(character, column * BLOC_WIDTH, line * BLOC_HEIGHT + BLOC_HEIGHT - getFontMetrics(font).getDescent());
                }
            }
        }
        return asciiImage;
    }

    private static char findBestCharForBrightness(String allowedCharacters, int[] characterBrightness, int targetBrightness) {
        int bestIndex = 0;
        int bestDiff = Integer.MAX_VALUE;
        for(int i=0;i<characterBrightness.length;i++){
            int tmpDiff = Math.abs(characterBrightness[i] - targetBrightness);
            if(tmpDiff<bestDiff){
                bestIndex = i;
                bestDiff = tmpDiff;
            }
        }

        return allowedCharacters.charAt(bestIndex);
    }

    private static int findMaxBrightness(BufferedImage inGrey) {
        int maxBrightness = 0;
        for(int y=0;y<inGrey.getHeight();y++) {
            for (int x = 0; x < inGrey.getWidth(); x++) {
                Color pixelColor = new Color(inGrey.getRGB(x, y));
                int colorBrightness = (int)Math.round(getColorBrightness(pixelColor));
                if(colorBrightness > maxBrightness){
                    maxBrightness = colorBrightness;
                }
            }
        }
        return maxBrightness;
    }

    private static int findMinBrightness(BufferedImage inGrey) {
        int minBrightness = 255;
        for(int y=0;y<inGrey.getHeight();y++) {
            for (int x = 0; x < inGrey.getWidth(); x++) {
                Color pixelColor = new Color(inGrey.getRGB(x, y));
                int colorBrightness = (int)Math.round(getColorBrightness(pixelColor));
                if(colorBrightness < minBrightness){
                    minBrightness = colorBrightness;
                }
            }
        }
        return minBrightness;
    }

    private static BufferedImage convertGreyScale(BufferedImage originalImage) {
        BufferedImage inGrey = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<inGrey.getHeight();y++){
            for(int x=0;x<inGrey.getWidth();x++){
                Color pixelColor = new Color(originalImage.getRGB(x, y));
                int colorBrightness = (int)Math.round(getColorBrightness(pixelColor));
                inGrey.setRGB(x,y,new Color(colorBrightness,colorBrightness,colorBrightness).getRGB());
            }
        }
        return inGrey;
    }

    private static BufferedImage levelImage(BufferedImage inGrey, int minBrightness, int maxBrightness, int minCharBrightness, int maxCharBrightness) {
        BufferedImage inGreyLevelled = new BufferedImage(inGrey.getWidth(), inGrey.getHeight(), BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<inGrey.getHeight();y++){
            for(int x=0;x<inGrey.getWidth();x++){
                Color pixelColor = new Color(inGrey.getRGB(x, y));
                int colorBrightness = (int)Math.round(getColorBrightness(pixelColor));
                int levelledBrightness = convertScale(minBrightness, maxBrightness, colorBrightness, minCharBrightness, maxCharBrightness);

                inGreyLevelled.setRGB(x, y, new Color(levelledBrightness, levelledBrightness, levelledBrightness).getRGB());
            }
        }
        return inGreyLevelled;
    }

    private static int[][] generateGreyBlocs(int BLOC_WIDTH, int BLOC_HEIGHT, int nbBlocsLine, int nbBlocsColumn, BufferedImage inGreyLevelled) {
        int[][] brightness = new int[nbBlocsColumn][nbBlocsLine];
        for(int line=0;line<nbBlocsColumn;line++){
            for(int column=0;column<nbBlocsLine;column++){
                int blocBrightness = getBlocBrightness(BLOC_WIDTH, BLOC_HEIGHT, column, line, inGreyLevelled);
                brightness[line][column] = blocBrightness;
            }
        }

        return brightness;
    }

    private static int getBlocBrightness(int BLOC_WIDTH, int BLOC_HEIGHT, int column, int line, BufferedImage inGreyLevelled) {
        int sumPixelsBrightness = 0;
        int nbPixels = 0;
        for(int yInBloc=0;yInBloc<BLOC_HEIGHT;yInBloc++) {
            for (int xInBloc = 0; xInBloc < BLOC_WIDTH; xInBloc++) {
                int x = column * BLOC_WIDTH + xInBloc;
                int y = line * BLOC_HEIGHT + yInBloc;
                if(x>=0 && x<inGreyLevelled.getWidth() && y >=0 && y<inGreyLevelled.getHeight()) {
                    nbPixels++;
                    Color pixelColor = new Color(inGreyLevelled.getRGB(x, y));
                    int pixelBrightness = (pixelColor.getRed() + pixelColor.getGreen() + pixelColor.getBlue()) / 3;
                    sumPixelsBrightness += pixelBrightness;
                }
            }
        }
        return (int) Math.round(sumPixelsBrightness / (double) nbPixels);
    }

    private static void createWindow() throws ClassNotFoundException, UnsupportedLookAndFeelException, InstantiationException, IllegalAccessException {
        UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());

        window = new JFrame();
        labelImage = new JLabel();

        JScrollPane scrollPane = new JScrollPane(labelImage);

        JPanel panelConfig = createPanelConfig();

        window.setLayout(new BorderLayout());
        window.add(scrollPane, BorderLayout.CENTER);
        window.add(panelConfig, BorderLayout.PAGE_START);
        window.pack();
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.setVisible(true);
    }

    private static JPanel createPanelConfig() {
        JPanel panelConfig = new JPanel();

        createFileChooser(panelConfig);
        createFontSizeConfig(panelConfig);
        createAllowedCharacters(panelConfig);
        return panelConfig;
    }

    private static void createAllowedCharacters(JPanel panelConfig) {
        JLabel labelCharacters = new JLabel("Allowed characters :");

        JTextField charactersField = new JTextField(allowedCharacters);
        charactersField.getDocument().addDocumentListener(new DocumentListener() {
            private void updateCharacters() {
                String text = charactersField.getText();
                allowedCharacters = text;
                updateImage();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                updateCharacters();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateCharacters();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateCharacters();
            }
        });

        panelConfig.add(labelCharacters);
        panelConfig.add(charactersField);
    }

    private static void createFileChooser(JPanel panelConfig) {
        JButton buttonFile = new JButton("Choose picture ...");
        buttonFile.addActionListener((event) -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg"));
            int res = fileChooser.showOpenDialog(window);
            if (res == JFileChooser.APPROVE_OPTION) {
                imageFile = Optional.of(fileChooser.getSelectedFile());
                updateImage();
            }
        });

        panelConfig.add(buttonFile);
    }

    private static void createFontSizeConfig(JPanel panelConfig) {
        JSlider selectFontSize = new JSlider(1,12,font.getSize());
        selectFontSize.addChangeListener((event)->{
            int fontSize = selectFontSize.getValue();
            font = new Font(FONT_NAME, Font.PLAIN, fontSize);
            updateImage();
        });

        panelConfig.add(selectFontSize);
    }

    private static void updateImage() {
        if(imageFile.isPresent()) {
            if(updateThread != null && !updateThread.isCancelled() && !updateThread.isDone()) {
                updateThread.cancel(true);
                System.out.println("update thread interrupted");
            }
            updateThread = new SwingWorker<BufferedImage, Object>(){

                @Override
                protected BufferedImage doInBackground() throws Exception {
                    return generateImage(allowedCharacters, imageFile.get(), font);
                }

                @Override
                protected void done() {
                    try {
                        BufferedImage asciiImage = get();
                        showImage(asciiImage);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    } catch (CancellationException e){
                        System.out.println("CANCELLED");
                    }
                }
            };
            updateThread.execute();
        }
    }

    private static double getColorBrightness(Color pixelColor) {
        return 0.2126*pixelColor.getRed() + 0.7152*pixelColor.getGreen() + 0.0722*pixelColor.getBlue();
    }


    private static int convertScale(int fromMin, int fromMax, int fromValue, int toMin, int toMax){
        int fromRange = fromMax - fromMin;
        int distLeftFrom = fromValue-fromMin;
        double pourcentInRangeFrom = (double)distLeftFrom/(double)fromRange;

        int toRange = toMax - toMin;
        return (int)Math.round(toMin + pourcentInRangeFrom*toRange);
    }
}
