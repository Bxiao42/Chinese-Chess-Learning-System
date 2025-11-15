# Chinese Chess Learning System (Xiangqi AI + Interactive Web UI)

A full-stack Chinese Chess (Xiangqi) learning and playing system built with **Spring Boot**, **Java**, **HTML/JS**, and integrated with the **Pikafish Engine** (one of the strongest open-source Xiangqi engines).

This project provides:
- Human vs AI gameplay
- Endgame (puzzle) solving mode
- Illegal-move detection
- Custom position loading
- Game history logging (CSV)
- A complete front-end interactive board with piece images

---

## Features

### **Gameplay Modes**
- **Human vs AI** — Real-time interaction with Pikafish engine  
- **Endgame Mode** — Load preset endgame layouts (1–10 levels)  
- **Custom Setup Mode** — User-configured board positions

### **AI Integration**
- Best move calculation
- AI responds automatically after player move
- Supports variable search depth / difficulty
- Works on any valid position (including endgames)

### **Rule Enforcement**
- Full Chinese Chess rule validation  
- Illegal move detection (no board update)  
- “Check” & “Checkmate” detection  
- Only legal moves allowed  
- Forced protection under check

### **Game Recording**
- Move history table  
- Best move comparison table  
- Illegal move counter  
- All logs saved to CSV for later analysis

### **Front-End UI**
- Click → move system  
- Canvas rendering  
- Highlight last move  
- Display piece icons  
- Smooth animations  
- Responsive layout

---
