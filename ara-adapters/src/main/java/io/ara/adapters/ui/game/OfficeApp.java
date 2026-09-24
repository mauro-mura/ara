package io.ara.adapters.ui.game;

import com.almasb.fxgl.app.GameApplication;
import com.almasb.fxgl.app.GameSettings;
import com.almasb.fxgl.entity.component.Component;
import io.ara.adapters.ui.game.AgentComponent;
import io.ara.adapters.ui.game.Painter;
import io.ara.adapters.ui.game.Room;
import io.ara.adapters.ui.game.Sprites;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.input.KeyCode;

import java.util.List;
import java.util.Map;

import static com.almasb.fxgl.dsl.FXGL.entityBuilder;
import static com.almasb.fxgl.dsl.FXGL.getGameScene;
import static com.almasb.fxgl.dsl.FXGL.onKey;

/**
 * Three colleagues in an office: developer and analyst at the desk, plus RAG,
 * the little pod robot that commutes between the KB-001 rack cabinet and the
 * developer's desk on its retrieval errands.
 *
 * The graphics are 240x136 pixel art; the FXGL viewport scales it by SCALE,
 * so all coordinates in the code stay in logical pixels.
 *
 * D key: stops or resumes the developer's movement (role DEV), pilots
 * UiSettings at runtime and the scene adapts on the next frame.
 * M key: queues a development-activity message sequence to the DEV, delivered
 * one message at a time 5 seconds apart.
 *
 * Above the leftmost rack cabinet a dynamic badge reads KB-001; it is driven
 * by settings.label(DEVICE_LABEL_ID), so it can be repurposed at runtime like
 * the role tags.
 */
public class OfficeApp extends GameApplication {

    /**
     * Runtime-pilotable panel, passed to the components and keys. Initialized
     * here and not in initGame because initInput, where the D key is bound,
     * runs first: the toggle must be able to read and write the state already
     * in the input phase. The two Maps are the initial state of tags and
     * speech-bubble messages.
     */
    public UiSettings settings = new UiSettings(
            Map.of("DEV", "Dev", "ANL", "Ana"),
            Map.of(
                    "DEV", List.of("java class Person write!", "change surname method", "add toString()", "add new method Age()"),
                    "ANL", List.of("KPI +12%", "report", "trend ok", "dati?"),
                    "RAG", List.of("retrieval!", "top-k 5", "rerank", "chunk")));

    private static final int SCALE = 3;

    /** Badge above the leftmost rack cabinet; label() defaults to the id itself. */
    private static final String DEVICE_LABEL_ID = "KB-001";

    /** Commute route of the RAG bot: KB-001 rack to the developer's desk edge. */
    private static final int[][] RAG_ROUTE_FEET = {
            {22, 110}, {132, 110}
    };

    /** RAG walks 50% faster than the base pace of the other walkers. */
    private static final double RAG_SPEED = AgentComponent.SPEED * 1.5;

    /** Height in pixels of a standing character: head (13) + body (12). */
    private static final int WALKER_H = 25;

    @Override
    protected void initSettings(GameSettings settings) {
        settings.setWidth(Room.W * SCALE);
        settings.setHeight(Room.H * SCALE);
        settings.setTitle("Ufficio IT");
        settings.setVersion("1.0");
    }

    @Override
    protected void initInput() {
        // D: stops or resumes the developer, to watch the UI adapt at runtime
        onKey(KeyCode.D, () -> settings.setMoving("DEV", !settings.isMoving("DEV")));
        // M: queues a development-activity sequence to the DEV
        onKey(KeyCode.M, () -> settings.enqueueMessages("DEV",
                List.of("fix build", "refactor", "test ok", "code review", "deploy")));
    }

    @Override
    protected void initGame() {
        getGameScene().setBackgroundColor(Painter.PAL[0]);
        getGameScene().getViewport().setZoom(SCALE);

        // --- palettes of the characters -----------------------------------
        Map<Character, Color> dev = Painter.palette(
                'S', 4, 's', 3, 'H', 0, 'h', 15, 'E', 0, 'W', 12, 'M', 2, 'B', 3, 'Q', 10,
                'C', 1, 'c', 1, 'V', 12, 'P', 15, 'O', 0, 'G', 0, 'L', 11);

        Map<Character, Color> anl = Painter.palette(
                'S', 4, 's', 3, 'H', 2, 'h', 1, 'E', 0, 'W', 12, 'M', 2, 'B', 3, 'Q', 14,
                'C', 12, 'c', 13, 'V', 13, 'P', 8, 'O', 0, 'G', 0, 'L', 11);

        Map<Character, Color> bot = Painter.palette(
                'M', 1, 'R', 11, 'B', 7, 'W', 12, 'E', 0, 'A', 13,
                'S', 6, 's', 2, 'V', 5, 'v', 8, 'O', 10);

        // --- room layers ----------------------------------------------------
        entityBuilder()
                .at(0, 0)
                .view(pixelView(Room.background()))
                .zIndex(-100)
                .buildAndAttach();

        // badge above the leftmost rack cabinet: dynamic like the role tags
        Text badge = new Text(settings.label(DEVICE_LABEL_ID));
        badge.setFont(Font.font("Monospaced", FontWeight.BOLD, 5));
        badge.setFill(Painter.PAL[5]);
        badge.setX(9);
        badge.setY(48);
        entityBuilder()
                .at(0, 0)
                .view(badge)
                .with(new DynamicLabelComponent(badge, settings, DEVICE_LABEL_ID))
                .zIndex(5)
                .buildAndAttach();

        // the two seated go between the background and the desk
        seated("Sviluppatore", "DEV", Sprites.HEAD_DEV, dev, 146, 63, 1);
        seated("Analista", "ANL", Sprites.HEAD_ANL, anl, 188, 63, 13);

        entityBuilder()
                .at(0, 0)
                .view(pixelView(Room.deskForeground()))
                .zIndex(20)
                .buildAndAttach();

        ImageView overlay = pixelView(Room.overlay(0));
        entityBuilder()
                .at(0, 0)
                .view(overlay)
                .with(new OverlayComponent(overlay))
                .zIndex(25)
                .buildAndAttach();

        // --- the retrieval bot -------------------------------------------------
        // RAG is drawn as the little pod robot; it shuttles from the KB-001 rack
        // to the developer's desk and back. It spawns exactly on the first
        // waypoint: an off-route Y would make it climb down to the path at
        // startup, bending the apparently straight line.
        // Arriving at KB-001 it runs a search, arriving at the desk it states
        // the coding guideline it just applied.
        List<Point2D> ragRoute = List.of(
                topLeft(RAG_ROUTE_FEET[0]), topLeft(RAG_ROUTE_FEET[1]));
        ragWalker("Bibliotecario", "RAG", bot,
                (int) ragRoute.get(0).getX(), (int) ragRoute.get(0).getY(), 5, ragRoute, 0,
                List.of("find('Best practise per il codice')",
                        "Funzioni corte, singola responsabilità"), RAG_SPEED);
    }

    // ---------------------------------------------------------------------

    private static Point2D topLeft(int[] feet) {
        return new Point2D(feet[0], feet[1] - WALKER_H);
    }

    /** ImageView without interpolation: that is what keeps crisp edges under zoom. */
    private static ImageView pixelView(Image image) {
        ImageView view = new ImageView(image);
        view.setSmooth(false);
        return view;
    }

    private void seated(String nome, String ruolo, String[] head, Map<Character, Color> pal,
                        int x, int y, int tinta) {
        Image[] frames = {
                Painter.sprite(head, Sprites.BODY_SIT_1, pal),
                Painter.sprite(head, Sprites.BODY_SIT_2, pal)
        };
        attach(nome, ruolo, frames, pal, x, y, tinta, true, List.of(), 0, null,
                AgentComponent.SPEED, 10);
    }

    /** RAG is drawn as the little robot: the hover frame pair makes the thruster
     * "breathe" and the antenna pulse while it moves. */
    private void ragWalker(String nome, String ruolo, Map<Character, Color> pal,
                           int x, int y, int tinta, List<Point2D> route, int start,
                           List<String> waypointMessages, double speed) {
        Image[] frames = {
                Painter.sprite(Sprites.HEAD_BOT_A, Sprites.BODY_BOT_HOVER_A, pal),
                Painter.sprite(Sprites.HEAD_BOT_B, Sprites.BODY_BOT_HOVER_B, pal)
        };
        attach(nome, ruolo, frames, pal, x, y, tinta, false, route, start, waypointMessages,
                speed, 50);
    }

    /**
     * Builds the entity: sprite + role tag + bubble, all children of the same
     * Group so they follow the character with no further code.
     */
    private void attach(String nome, String ruolo, Image[] frames, Map<Character, Color> pal,
                        int x, int y, int tinta, boolean seatedAgent, List<Point2D> route,
                        int start, List<String> waypointMessages, double speed, int z) {

        ImageView sprite = pixelView(frames[0]);

        Text label = new Text(settings.label(ruolo));
        label.setFont(Font.font("Monospaced", FontWeight.BOLD, 4));
        label.setFill(Painter.PAL[tinta]);
        label.setX(1);
        label.setY(-2);

        Text bubbleText = new Text("");
        bubbleText.setFont(Font.font("Monospaced", 4));
        bubbleText.setFill(Painter.PAL[0]);
        bubbleText.setY(7);

        Rectangle box = new Rectangle(20, 10, Painter.PAL[12]);
        box.setStroke(Painter.PAL[0]);
        box.setStrokeWidth(0.5);

        Group bubble = new Group(box, bubbleText);
        bubble.setTranslateY(-14);
        bubble.setVisible(false);

        Group view = new Group(sprite, label, bubble);

        entityBuilder()
                .at(x, y)
                .view(view)
                .with(new AgentComponent(sprite, bubble, box, bubbleText, label,
                        frames, seatedAgent, route, start, settings, ruolo,
                        waypointMessages, speed))
                .zIndex(z)
                .buildAndAttach();
    }

    /** Keeps a decorative Text in sync with a UiSettings label, per frame. */
    private static class DynamicLabelComponent extends Component {

        private final Text label;
        private final UiSettings settings;
        private final String id;

        DynamicLabelComponent(Text label, UiSettings settings, String id) {
            this.label = label;
            this.settings = settings;
            this.id = id;
        }

        @Override
        public void onUpdate(double tpf) {
            String text = settings.label(id);
            if (!label.getText().equals(text)) {
                label.setText(text);
            }
        }
    }

    /** Regenerates the animated layer (LEDs, clock hands, screens) at regular intervals. */
    private static class OverlayComponent extends Component {

        private final ImageView view;
        private double time;
        private double since;

        OverlayComponent(ImageView view) {
            this.view = view;
        }

        @Override
        public void onUpdate(double tpf) {
            time += tpf;
            since += tpf;
            if (since >= 0.15) {
                since = 0;
                view.setImage(Room.overlay(time));
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}