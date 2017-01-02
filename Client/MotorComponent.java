import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;

public class MotorComponent extends JPanel implements ChangeListener, ActionListener, Stringable {
    private String name;
    private JSlider slider;
    private JButton delete;
    private JButton changeBounds;
    private Consumer<Double> callback;
    private Runnable onDelete;
    private double lowerBound = -1;
    private double higherBound = 1;
    private static final int DEFAULT_LOWER_BOUND = -10; //just # ticks in slider
    private static final int DEFAULT_HIGHER_BOUND = 10;
    private static final int DEFAULT_BOUND_DIFFERENCE = DEFAULT_HIGHER_BOUND-DEFAULT_LOWER_BOUND;
    
    public MotorComponent(String name, Consumer<Double> callback, Runnable onDelete) {
        this.name = name;
        this.callback = callback;
        this.onDelete = onDelete;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                initGUI();
            }
        });
    }
    
    public String getName() {
        return name;
    }
    
    public String getStringRepr() {
        return lowerBound + "|" + higherBound + "|" + name;
    }
    
    public void setBounds(double lowerBound, double higherBound) {
        this.lowerBound = lowerBound;
        this.higherBound = higherBound;
        System.out.println("LOWER: " + lowerBound + " HIGHER: " + higherBound);
    }
    
    public static MotorComponent buildFromString(String repr, Function<String, MotorComponent> constructor) {
        String[] split = repr.split("\\|");
        double lowerBound = Double.parseDouble(split[0]);
        double higherBound = Double.parseDouble(split[1]);
        String name = split[2];
        MotorComponent ret = constructor.apply(name);
        ret.setBounds(lowerBound, higherBound);
        return ret;
    }
    
    public void initGUI() {
        setLayout(new BorderLayout());
        
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BorderLayout());
        bottomPanel.add(new JLabel(name), BorderLayout.CENTER);
        JPanel bottomLeftPanel = new JPanel();
        bottomLeftPanel.setLayout(new BorderLayout());
        bottomLeftPanel.add((delete = new JButton("x")), BorderLayout.EAST);
        bottomLeftPanel.add((changeBounds = new JButton("0.0")), BorderLayout.CENTER);
        bottomPanel.add(bottomLeftPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.NORTH);
        
        delete.setActionCommand("delete");
        delete.addActionListener(this);
        changeBounds.setActionCommand("bounds");
        changeBounds.addActionListener(this);
        
        slider = new JSlider(JSlider.HORIZONTAL, DEFAULT_LOWER_BOUND, DEFAULT_HIGHER_BOUND, 0);
        slider.addChangeListener(this);
        
        add(slider, BorderLayout.CENTER);
    }
    
    @Override
    public void stateChanged(ChangeEvent e) {
        //slider has been slid
        
        //normalize value
        double value = scale(slider.getValue(), DEFAULT_LOWER_BOUND, DEFAULT_HIGHER_BOUND, lowerBound, higherBound);
        
        changeBounds.setText(String.valueOf(value));
        callback.accept(value);
    }
    
    @Override
    public void actionPerformed(ActionEvent ae) {
        String command = ae.getActionCommand();
        if(command.equals("delete")) {
            onDelete.run();
        } else if(command.equals("bounds")) {
            try {
                lowerBound = Double.valueOf(JOptionPane.showInputDialog("Enter lower bound:"));
                higherBound = Double.valueOf(JOptionPane.showInputDialog("Enter higher bound:"));
            } catch(NumberFormatException nfe) {
                JOptionPane.showMessageDialog(null,
                    "Not a double.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            } catch(NullPointerException npe) {
                //user closed window, do nothing
            }
        } else {
            System.err.println("Unsupported action command: " + command);
        }
    }
    
    private static double scale(final double valueIn, final double baseMin, final double baseMax, final double limitMin, final double limitMax) {
        return Math.round((((limitMax - limitMin) * (valueIn - baseMin) / (baseMax - baseMin)) + limitMin)*100)/100.0;
    }
}